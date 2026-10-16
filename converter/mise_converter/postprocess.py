"""Turn a model response into a finished recipe — deterministically, without trusting the model."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

from mise_shared import SCHEMA_VERSION
from mise_shared.util import now_iso, slugify
from mise_shared.validation import validate_recipe

from .inputs import SourceDocument
from .llm_schema import normalize


@dataclass
class Outcome:
    recipe: dict[str, Any] | None = None
    errors: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return self.recipe is not None and not self.errors


def parse_output(text: str | None, stop_reason: str | None) -> tuple[dict[str, Any] | None, list[str]]:
    if stop_reason == "refusal":
        return None, ["the model declined to convert this text (stop_reason: refusal)"]
    if stop_reason == "max_tokens":
        return None, ["the output hit max_tokens and was cut off; raise converter.max_tokens in config.toml"]
    if not text:
        return None, ["the response contained no JSON text"]
    try:
        data = json.loads(text)
    except json.JSONDecodeError as e:
        return None, [f"output is not valid JSON: {e}"]
    if not isinstance(data, dict):
        return None, ["output JSON is not an object"]
    return data, []


def unique_id(proposed: str, taken: set[str]) -> str:
    base = slugify(proposed)
    rid, n = base, 2
    while rid in taken:
        rid, n = f"{base}-{n}", n + 1
    return rid


def finalize(
    llm_output: dict[str, Any],
    doc: SourceDocument,
    *,
    recipe_id: str,
    created_at: str | None = None,
) -> Outcome:
    """Normalize, add the fields code owns, validate, and turn warnings into review flags."""
    recipe = normalize(llm_output)
    recipe["id"] = recipe_id
    recipe["source"]["original_filename"] = doc.filename
    now = now_iso()
    full: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        **recipe,
        "source_text_sha256": doc.sha256,
        "status": "needs_review",
        "created_at": created_at or now,
        "updated_at": now,
    }
    full["related_recipes"] = [slugify(r) for r in full.get("related_recipes", []) if slugify(r) != recipe_id]

    result = validate_recipe(full)
    if not result.ok:
        return Outcome(recipe=full, errors=result.errors)

    existing = {(f["path"], f["message"]) for f in full["review_flags"]}
    for path, message in result.warnings:
        if (path, message) not in existing:
            full["review_flags"].append({"path": path, "message": message})
    full["status"] = "needs_review" if full["review_flags"] else "ready"
    return Outcome(recipe=full)
