"""Writing a new recipe from a short request ("a quick dal for 4"), with the converter's format rules.

The result is an ordinary recipe file in recipes/, always marked "needs review" with a note that
Claude wrote it, plus <id>.source.txt holding the request (so "View original" shows what was asked).
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any, Callable

from mise_shared.config import Config
from mise_shared.paths import PROMPTS_DIR
from mise_shared.util import now_iso, sha256_text, write_json_atomic, write_text_atomic

from .client import LLMClient
from .inputs import SourceDocument
from .llm_schema import LLM_RECIPE_SCHEMA
from .postprocess import finalize, parse_output, unique_id
from .prompt import system_prompt

PROMPT_FILE = PROMPTS_DIR / "write_recipe.md"
MAX_REQUEST = 2000

GENERATED_FLAG = {
    "path": "source",
    "message": "Written by Claude from your request, not taken from a tested recipe. Check the amounts, "
    "times and seasoning (and anything the other notes mention) before you rely on it.",
}


@lru_cache(maxsize=1)
def writer_prompt() -> str:
    template = PROMPT_FILE.read_text(encoding="utf-8")
    template = re.sub(r"^<!--.*?-->\s*", "", template, flags=re.S)
    return template.replace("{{CONVERSION_GUIDE}}", system_prompt())


def writer_prompt_version() -> str:
    m = re.search(r"prompt_version:\s*(\S+)", PROMPT_FILE.read_text(encoding="utf-8"))
    return m.group(1) if m else "unversioned"


def request_text(request: str) -> str:
    """What's kept as the recipe's 'original text'."""
    return f"Requested in Mise on {now_iso()[:10]}:\n\n{request.strip()}\n"


@dataclass
class Generated:
    recipe_id: str | None
    errors: list[str]


def generate_recipe(
    cfg: Config,
    client: LLMClient,
    request: str,
    *,
    out_dir: Path | None = None,
    log: Callable[[str], None] = print,
) -> Generated:
    """Ask for a recipe; validate it (one retry with the problems); write it. Never overwrites a recipe."""
    request = request.strip()
    if not request:
        return Generated(None, ["the request is empty"])
    out_dir = out_dir or cfg.recipes_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    text = request_text(request)
    doc = SourceDocument(path=Path("request.txt"), key="request.txt", text=text, sha256=sha256_text(text))
    c = cfg.converter
    messages: list[dict[str, Any]] = [
        {"role": "user", "content": [{"type": "text", "text": f"<request>\n{request[:MAX_REQUEST]}\n</request>"}]}
    ]
    errors: list[str] = []
    for attempt in (1, 2):
        params = {
            "model": c.model,
            "max_tokens": c.max_tokens,
            "system": [{"type": "text", "text": writer_prompt(), "cache_control": {"type": "ephemeral"}}],
            "messages": messages,
            "thinking": {"type": "adaptive"},
            "output_config": {"effort": c.effort, "format": {"type": "json_schema", "schema": LLM_RECIPE_SCHEMA}},
        }
        response = client.create(params)
        if response.error:
            return Generated(None, [response.error])
        data, errors = parse_output(response.text, response.stop_reason)
        if data is not None:
            taken = {p.stem for p in out_dir.glob("*.json") if not p.name.endswith(".user.json")}
            rid = unique_id(str(data.get("title") or "recipe"), taken)
            outcome = finalize(data, doc, recipe_id=rid)
            errors = outcome.errors
            if outcome.ok:
                recipe = outcome.recipe
                recipe["source"] = {"type": "other"}
                recipe["review_flags"].insert(0, dict(GENERATED_FLAG))
                recipe["status"] = "needs_review"
                write_text_atomic(out_dir / f"{rid}.source.txt", text)
                write_json_atomic(out_dir / f"{rid}.json", recipe)
                log(f"  ✓ wrote {rid}.json from the request")
                return Generated(rid, [])
        if attempt == 1 and response.text:
            log(f"  {len(errors)} problem(s) with the written recipe; asking for a fix")
            messages = messages + [
                {"role": "assistant", "content": [{"type": "text", "text": response.text}]},
                {"role": "user", "content": [{"type": "text", "text": (
                    "Your JSON failed validation:\n" + "\n".join(f"- {e}" for e in errors) +
                    "\n\nFix these problems and output the complete corrected recipe JSON."
                )}]},
            ]
            continue
        break
    return Generated(None, errors or ["no recipe came back"])
