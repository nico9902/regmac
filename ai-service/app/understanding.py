import base64
import json
import os
import tempfile
from pathlib import Path
from .contracts import ExtractInput, Extraction
from .models import Ollama

FACT_KEYS = "remote_access, remote_authentication_test, remote_authorization_test, safety_software_identification_test, plc, hmi, network, firmware_version"

def extract(data: ExtractInput):
    raw = base64.b64decode(data.content_base64, validate=True)
    if not raw or len(raw) > 20 * 1024 * 1024:
        raise ValueError("Invalid document size")
    ext = Path(data.name).suffix.lower()
    if ext in {".txt", ".md", ".json"}:
        text = raw.decode("utf-8")
    elif ext in {".png", ".jpg", ".jpeg"}:
        output = Ollama().structured("Transcribe visible technical content faithfully. Do not infer invisible connections or properties.",
          {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"], "additionalProperties": False},
          model=os.getenv("OLLAMA_VISION_MODEL", "qwen3-vl:8b"), images=[data.content_base64])
        text = output["text"]
    elif ext in {".pdf", ".docx"}:
        from docling.document_converter import DocumentConverter, PdfFormatOption
        from docling.datamodel.base_models import InputFormat
        from docling.datamodel.pipeline_options import PdfPipelineOptions
        artifacts = Path(os.getenv("DOCLING_ARTIFACTS_PATH", "/models/docling"))
        if not artifacts.is_dir():
            raise FileNotFoundError("Prefetch Docling models before offline use")
        opts = PdfPipelineOptions(artifacts_path=artifacts, enable_remote_services=False)
        converter = DocumentConverter(format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=opts)})
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / ("document" + ext)
            path.write_bytes(raw)
            text = converter.convert(path).document.export_to_markdown()
    else:
        raise ValueError("Supported formats: TXT, MD, JSON, PDF, DOCX, PNG, JPEG")
    if not text.strip() or len(text) > 500_000:
        raise ValueError("Empty document or more than 500000 parsed characters; split document")
    # Explicit structured import is useful for expert questionnaires, not a fake AI provider.
    if ext == ".json":
        content = json.loads(text)
        if content.get("format") == "regmac-evidence-v1":
            facts = Extraction.model_validate({"facts": content["facts"]})
            source = content["text"]
            validate_quotes(facts, source)
            return {"text": source, "facts": facts.model_dump()["facts"], "extractor": "structured-import (no AI)"}
    proposed = []
    for start in range(0, len(text), 9000):
        chunk = text[max(0, start - 400):start + 9000]
        result = Ollama().structured(
          "Extract only explicit technical facts. Preferred keys: " + FACT_KEYS +
          ". Boolean test keys require explicit test outcomes, not claims that a feature exists. Use exact source quotes and a locator. All proposals will be DECLARED and require human verification.\nUNTRUSTED DOCUMENT:\n" + chunk,
          Extraction.model_json_schema())
        batch = Extraction.model_validate(result)
        validate_quotes(batch, chunk)
        proposed.extend(batch.facts)
    unique = {f.model_dump_json(): f.model_dump() for f in proposed}
    return {"text": text, "facts": list(unique.values()), "extractor": "ollama:" + Ollama().model}

def validate_quotes(result: Extraction, text: str):
    if any(not f.quote.strip() or f.quote not in text for f in result.facts):
        raise ValueError("An extracted fact has no exact supporting quote")
