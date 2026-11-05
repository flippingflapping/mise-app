"""Tips and ideas for making a recipe better ("toast the cumin first"), from Claude, cached per recipe."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from .assist import JsonCache, ask_json, key_of, recipe_as_text
from .client import LLMClient

KINDS = ["technique", "flavour", "time-saver", "make-ahead", "serving", "equipment"]

SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["tips"],
    "properties": {
        "tips": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["kind", "title", "detail"],
                "properties": {
                    "kind": {"type": "string", "enum": KINDS},
                    "title": {"type": "string", "description": "The tip in a few words, e.g. 'Toast the whole spices'."},
                    "detail": {"type": "string", "description": "One to three sentences: what to do, when, and why."},
                },
            },
        }
    },
}

SYSTEM = """You are an experienced home cook and cookery teacher. Given a recipe, suggest 4 to 6 tips that
would make it turn out better or make it easier, best first. Mix the kinds that fit this dish:
* technique: how to do a step better ("brown the lamb in two batches so it sears, not stews").
* flavour: a small change that lifts it ("a squeeze of lime at the end"). Keep the dish what it is.
* time-saver: a shortcut that doesn't hurt the result.
* make-ahead: what can be done earlier, and how it keeps.
* serving: what to serve with it, how to finish or garnish it.
* equipment: a pan or tool that helps, only if it matters.

Be specific to this recipe: name its ingredients and steps ("in step 4…"). Each tip: a short `title`
and a `detail` of one to three plain sentences saying what to do and why. Don't repeat what the recipe
already says, don't suggest anything unsafe, and keep the diet of the dish (a vegetarian recipe stays
vegetarian)."""


class TipFinder:
    def __init__(self, state_dir: Path, model: str, effort: str = "low"):
        self.cache = JsonCache(state_dir / "tips.json")
        self.model = model
        self.effort = effort

    @staticmethod
    def key(recipe: dict[str, Any]) -> str:
        return key_of(recipe.get("id"), recipe.get("title"), recipe.get("ingredients"), recipe.get("steps"))

    def cached(self, recipe: dict[str, Any]) -> list[dict[str, Any]] | None:
        return self.cache.get(self.key(recipe))

    def find(self, client: LLMClient, recipe: dict[str, Any]) -> list[dict[str, Any]]:
        """Tips for `recipe` (edits already applied). Raises RuntimeError if the answer can't be used."""
        key = self.key(recipe)
        hit = self.cache.get(key)
        if hit is not None:
            return hit
        data = ask_json(client, model=self.model, effort=self.effort, system=SYSTEM, user=recipe_as_text(recipe), schema=SCHEMA)
        try:
            tips = [
                {
                    "kind": t.get("kind") if t.get("kind") in KINDS else "technique",
                    "title": str(t.get("title") or "").strip(),
                    "detail": str(t.get("detail") or "").strip(),
                }
                for t in data["tips"]
                if isinstance(t, dict)
            ]
        except (KeyError, TypeError) as e:
            raise RuntimeError(f"unexpected answer: {e}") from e
        tips = [t for t in tips if t["title"] and t["detail"]]
        self.cache.put(key, tips)
        return tips
