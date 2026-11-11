from typing import Any, Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator
class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")
class Question(Strict):
    id: str
    type: Literal["CHOICE", "NOUL", "SCORE"]
    instructions: str
    criteria: dict[str, str] | None = None
    levels: list[str] | None = None
class Task(Strict):
    requirement_id: str
    requirement_version: str
    control_id: str
    applicability: str
    machine_state: dict[str, Any]
    evidence: list[dict[str, Any]]
    questions: list[Question]
class Fact(Strict):
    subject: str = "machine"
    key: str = Field(pattern=r"^[a-z0-9_.-]+$")
    value: bool | str | float
    quote: str = Field(min_length=1)
    locator: str = "document"
class Extraction(Strict):
    facts: list[Fact]
class ExtractInput(Strict):
    name: str
    content_base64: str = Field(max_length=28_000_000)
class Decision(Strict):
    provider: str
    model: str
    status: Literal["OK", "UNAVAILABLE", "INVALID_OUTPUT"]
    calibrated: bool = False
    abstain: bool = True
    answers: dict[str, Any] = Field(default_factory=dict)
    error: str | None = None
