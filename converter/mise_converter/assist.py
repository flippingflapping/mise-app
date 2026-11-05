"""Shared bits for the small "ask Claude about this recipe" features (substitutes, tips).

Each is one structured-output call with the recipe as plain text, answers cached in a JSON file in
recipes/.mise/ keyed by what was asked, so asking again is instant and an edited recipe asks afresh.
"""

from __future__ import annotations

import hashlib
import json
import threading
from pathlib import Path
from typing import Any

from mise_shared.util import write_json_atomic

from .client import LLMClient


def amount_text(i: dict[str, Any]) -> str:
    """ "500 g ", "2 ", or "" (to taste)."""
    amount = i.get("amount")
    parts = [f"{amount:g}" if isinstance(amount, (int, float)) else None, i.get("unit")]
    parts = [str(x) for x in parts if x not in (None, "")]
    return " ".join(parts) + " " if parts else ""


def recipe_as_text(recipe: dict[str, Any]) -> str:
    """The recipe as a cook would read it (placeholders left as {0001}, which the ingredient list explains)."""
    lines = [f"Recipe: {recipe.get('title')}", f"Makes: {recipe.get('yield', {}).get('base_servings')} servings"]
    if recipe.get("description"):
        lines.append(str(recipe["description"]))
    lines += ["", "Ingredients:"]
    for i in recipe.get("ingredients", []):
        lines.append(f"- {{{i.get('id')}}} = {amount_text(i)}{i.get('name')}" + (f", {i['prep']}" if i.get("prep") else ""))
    lines += ["", "Method:"]
    for n, s in enumerate(recipe.get("steps", []), 1):
        lines.append(f"{n}. {s.get('title')}: {s.get('content')}")
    if recipe.get("notes"):
        lines += ["", f"Author's notes: {recipe['notes']}"]
    return "\n".join(lines)


def key_of(*parts: Any) -> str:
    raw = json.dumps(parts, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]


class JsonCache:
    """A small thread-safe {key: answer} file."""

    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()

    def _load(self) -> dict[str, Any]:
        try:
            return json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {}

    def get(self, key: str) -> Any:
        with self._lock:
            return self._load().get(key)

    def put(self, key: str, value: Any) -> None:
        with self._lock:
            data = self._load()
            data[key] = value
            write_json_atomic(self.path, data)


def ask_json(
    client: LLMClient, *, model: str, effort: str, system: str, user: str, schema: dict[str, Any], max_tokens: int = 4000,
) -> dict[str, Any]:
    """One structured-output call. Raises RuntimeError with a readable message if it fails."""
    response = client.create({
        "model": model,
        "max_tokens": max_tokens,
        "system": [{"type": "text", "text": system}],
        "messages": [{"role": "user", "content": [{"type": "text", "text": user}]}],
        "output_config": {"effort": effort, "format": {"type": "json_schema", "schema": schema}},
    })
    if response.error:
        raise RuntimeError(response.error)
    try:
        data = json.loads(response.text or "")
    except json.JSONDecodeError as e:
        raise RuntimeError(f"unexpected answer: {e}") from e
    if not isinstance(data, dict):
        raise RuntimeError("unexpected answer: not a JSON object")
    return data
