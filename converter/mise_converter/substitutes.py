"""Ideas for swapping one ingredient in a recipe ("no fresh ginger?"), sized for that recipe.

One small structured-output call with the recipe as context. Answers are cached in
recipes/.mise/substitutes.json, keyed by the recipe and ingredient as they are now, so asking again
is instant and an edit to the recipe asks afresh.
"""

from __future__ import annotations

import hashlib
import json
import threading
from pathlib import Path
from typing import Any

from mise_shared.util import write_json_atomic

from .client import LLMClient

UNITS = ["g", "kg", "ml", "l", "tsp", "tbsp", "cup", "fl_oz", "oz", "lb", "pinch"]

SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["suggestions"],
    "properties": {
        "suggestions": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["name", "amount", "unit", "note"],
                "properties": {
                    "name": {"type": "string", "description": "What to use instead, as a shopper reads it."},
                    "amount": {"type": ["number", "null"], "description": "For the recipe as written; null = to taste."},
                    "unit": {"anyOf": [{"type": "string", "enum": UNITS}, {"type": "null"}]},
                    "note": {"type": "string", "description": "How it changes the dish, and any change to the method."},
                },
            },
        }
    },
}

SYSTEM = """You help home cooks swap an ingredient they don't have, can't eat or don't like.

Given a recipe and one of its ingredients, suggest 2 to 4 substitutes, best first. For each:
* `name`: what to use instead, as a shopper would read it ("garlic paste", "dried thyme").
* `amount` and `unit`: how much replaces the original amount in the recipe as written (it scales with
  the servings in the app). Use the original's unit when it makes sense; `unit` null means a count
  ("2 shallots"); `amount` null means "to taste". Account for strength (dried herbs are stronger than
  fresh, garlic powder is stronger than cloves).
* `note`: one or two plain sentences on how it changes the result and anything to do differently
  ("add it with the onions instead of at the end").

Prefer common supermarket ingredients. Respect what the dish is (keep a vegetarian recipe vegetarian).
If leaving the ingredient out is a good option, include it as a suggestion with the name "leave it out",
amount null, and say what to expect. Never suggest anything unsafe."""


def _context(recipe: dict[str, Any], ing: dict[str, Any]) -> str:
    lines = [f"Recipe: {recipe.get('title')}", f"Makes: {recipe.get('yield', {}).get('base_servings')} servings", "", "Ingredients:"]
    for i in recipe.get("ingredients", []):
        lines.append(f"- {_amount(i)}{i.get('name')}" + (f", {i['prep']}" if i.get("prep") else ""))
    lines += ["", "Method:"]
    for n, s in enumerate(recipe.get("steps", []), 1):
        lines.append(f"{n}. {s.get('content')}")
    lines += ["", f"Substitute for: {_amount(ing)}{ing.get('name')} (in the method it appears as {{{ing.get('id')}}})"]
    return "\n".join(lines)


def _amount(i: dict[str, Any]) -> str:
    """ "500 g ", "2 ", or "" (to taste)."""
    amount = i.get("amount")
    parts = [f"{amount:g}" if isinstance(amount, (int, float)) else None, i.get("unit")]
    parts = [str(x) for x in parts if x not in (None, "")]
    return " ".join(parts) + " " if parts else ""


def cache_key(recipe: dict[str, Any], ing: dict[str, Any]) -> str:
    raw = json.dumps({"recipe": recipe.get("id"), "title": recipe.get("title"), "ing": ing}, sort_keys=True)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]


class SubstituteFinder:
    def __init__(self, state_dir: Path, model: str, effort: str = "low"):
        self.path = state_dir / "substitutes.json"
        self.model = model
        self.effort = effort
        self._lock = threading.Lock()

    def _load(self) -> dict[str, Any]:
        try:
            return json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {}

    def cached(self, recipe: dict[str, Any], ingredient_id: str) -> list[dict[str, Any]] | None:
        ing = next((i for i in recipe.get("ingredients", []) if i.get("id") == ingredient_id), None)
        if ing is None:
            return None
        with self._lock:
            return self._load().get(cache_key(recipe, ing))

    def find(self, client: LLMClient, recipe: dict[str, Any], ingredient_id: str) -> list[dict[str, Any]]:
        """Suggestions for `ingredient_id` in `recipe` (edits already applied). Raises LookupError / RuntimeError."""
        ing = next((i for i in recipe.get("ingredients", []) if i.get("id") == ingredient_id), None)
        if ing is None:
            raise LookupError(f"no ingredient {ingredient_id!r} in this recipe")
        key = cache_key(recipe, ing)
        with self._lock:
            hit = self._load().get(key)
        if hit is not None:
            return hit
        response = client.create({
            "model": self.model,
            "max_tokens": 4000,
            "system": [{"type": "text", "text": SYSTEM}],
            "messages": [{"role": "user", "content": [{"type": "text", "text": _context(recipe, ing)}]}],
            "output_config": {"effort": self.effort, "format": {"type": "json_schema", "schema": SCHEMA}},
        })
        if response.error:
            raise RuntimeError(response.error)
        try:
            data = json.loads(response.text or "")
            suggestions = [
                {k: s.get(k) for k in ("name", "amount", "unit", "note")}
                for s in data["suggestions"]
                if isinstance(s, dict) and str(s.get("name") or "").strip()
            ]
        except (json.JSONDecodeError, KeyError, TypeError) as e:
            raise RuntimeError(f"unexpected answer: {e}") from e
        for s in suggestions:  # tidy what the schema can't promise
            if s["unit"] not in UNITS:
                s["unit"] = None
            if not isinstance(s["amount"], (int, float)) or s["amount"] <= 0:
                s["amount"] = None
            s["note"] = str(s.get("note") or "").strip()
        with self._lock:
            cache = self._load()
            cache[key] = suggestions
            write_json_atomic(self.path, cache)
        return suggestions
