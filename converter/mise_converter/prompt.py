"""Builds the (cacheable) system prompt and per-recipe requests."""

from __future__ import annotations

import json
import re
from functools import lru_cache
from typing import Any

from mise_shared.paths import FIXTURES_DIR, PROMPTS_DIR

from .inputs import SourceDocument
from .llm_schema import LLM_RECIPE_SCHEMA, SYSTEM_FIELDS, denormalize

PROMPT_FILE = PROMPTS_DIR / "convert_recipe.md"
EXAMPLE_RAW = FIXTURES_DIR / "raw" / "lamb-curry-for-kothu.txt"
EXAMPLE_EXPECTED = FIXTURES_DIR / "expected" / "sri-lankan-lamb-curry-for-kothu.json"


@lru_cache(maxsize=1)
def prompt_version() -> str:
    m = re.search(r"prompt_version:\s*(\S+)", PROMPT_FILE.read_text(encoding="utf-8"))
    return m.group(1) if m else "unversioned"


@lru_cache(maxsize=1)
def system_prompt() -> str:
    """The full system prompt with the worked example filled in. Deterministic (cache-friendly)."""
    template = PROMPT_FILE.read_text(encoding="utf-8")
    template = re.sub(r"^<!--.*?-->\s*", "", template, flags=re.S)
    expected = json.loads(EXAMPLE_EXPECTED.read_text(encoding="utf-8"))
    for f in SYSTEM_FIELDS:
        expected.pop(f, None)
    example_out = json.dumps(denormalize(expected), ensure_ascii=False, indent=1)
    example_in = EXAMPLE_RAW.read_text(encoding="utf-8").strip()
    return template.replace("{{EXAMPLE_INPUT}}", example_in).replace("{{EXAMPLE_OUTPUT}}", example_out)


def user_content(doc: SourceDocument) -> list[dict[str, Any]]:
    text = (
        f"Source file name: {doc.filename}\n\n"
        f"<recipe_text>\n{doc.text.strip()}\n</recipe_text>"
    )
    return [*doc.blocks, {"type": "text", "text": text}]


def retry_content(errors: list[str]) -> list[dict[str, Any]]:
    bullet = "\n".join(f"- {e}" for e in errors)
    return [
        {
            "type": "text",
            "text": (
                "Your JSON failed validation:\n"
                f"{bullet}\n\n"
                "Fix these problems and output the complete corrected recipe JSON. "
                "Keep everything else the same, and still invent nothing."
            ),
        }
    ]


def request_params(
    doc: SourceDocument,
    *,
    model: str,
    max_tokens: int,
    effort: str,
    history: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Messages API params. The system block carries cache_control; only messages vary."""
    messages = history or [{"role": "user", "content": user_content(doc)}]
    return {
        "model": model,
        "max_tokens": max_tokens,
        "system": [{"type": "text", "text": system_prompt(), "cache_control": {"type": "ephemeral"}}],
        "messages": messages,
        "thinking": {"type": "adaptive"},
        "output_config": {"effort": effort, "format": {"type": "json_schema", "schema": LLM_RECIPE_SCHEMA}},
    }
