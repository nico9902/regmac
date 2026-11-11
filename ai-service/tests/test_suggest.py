from app.suggest import SuggestInput, verify, suggest


def data():
    return SuggestInput.model_validate({
        "requirement": {"id": "MR-III-1.2.4.3", "law": "MR", "reference": "All. III, punto 1.2.4.3", "title": "Arresto di emergenza", "text": "..."},
        "excerpts": [{"evidenceId": "e1", "document": "manuale.pdf", "text": "Il pulsante di arresto di emergenza è conforme alla EN ISO 13850 ed è stato provato."}],
        "facts": []})


def test_only_literal_citations_survive():
    raw = {"proposal": "COMPLIANT", "rationale": "ok", "missing": [], "citations": [
        {"evidenceId": "e1", "quote": "arresto di emergenza è conforme alla EN ISO 13850"},
        {"evidenceId": "e1", "quote": "frase inventata che non esiste nel documento"},
        {"evidenceId": "e2", "quote": "arresto di emergenza è conforme"}]}
    out = verify(raw, data())
    assert out["proposal"] == "COMPLIANT"
    assert len(out["citations"]) == 1 and out["citations"][0]["document"] == "manuale.pdf"


def test_unsupported_proposal_becomes_insufficient():
    out = verify({"proposal": "COMPLIANT", "rationale": "x", "missing": [], "citations": [{"evidenceId": "e1", "quote": "inventata del tutto qui"}]}, data())
    assert out["proposal"] == "INSUFFICIENT" and out["citations"] == []


def test_unavailable_model_never_fabricates(monkeypatch):
    monkeypatch.setenv("OLLAMA_URL", "http://127.0.0.1:9")
    s = suggest(data())
    assert s.status == "UNAVAILABLE" and s.proposal is None
