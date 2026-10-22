"""Applying the app's recipe edits (user.edits / <id>.user.json) on top of a converted recipe.

The recipe file itself is never changed: edits live in the sidecar and are applied when needed, so
they can be undone and survive the file being re-read. The app does the same in Kotlin
(app.mise.core.edit.RecipeEditor); keep the two in step.
"""

from __future__ import annotations

import copy
from typing import Any

from .models import Edits
from .validation import validate_recipe

_INGREDIENT_FIELDS = ("prep", "amount", "amount_max", "unit", "optional")
_STEP_FIELDS = ("timer_seconds", "timer_max_seconds")


def apply_edits(recipe: dict[str, Any], edits: Edits | dict[str, Any] | None) -> dict[str, Any]:
    """A copy of `recipe` with `edits` applied. Edits for ingredients/steps that no longer exist are ignored."""
    if edits is None:
        return copy.deepcopy(recipe)
    e = edits if isinstance(edits, Edits) else Edits.model_validate(edits)
    out = copy.deepcopy(recipe)
    if e.title:
        out["title"] = e.title
    if e.yield_ is not None:
        out["yield"] = e.yield_.model_dump(mode="json", exclude_none=True)
    for ing in out.get("ingredients", []):
        change = e.ingredients.get(ing.get("id"))
        if change is None:
            continue
        if ing.get("short_name") == ing.get("name"):
            ing["short_name"] = change.name  # the short name followed the name, so keep following it
        ing["name"] = change.name
        values = change.model_dump(mode="json")
        for f in _INGREDIENT_FIELDS:
            v = values.get(f)
            if v is None and f not in ("amount", "unit"):
                ing.pop(f, None)
            else:
                ing[f] = v
    for step in out.get("steps", []):
        change = e.steps.get(step.get("id"))
        if change is None:
            continue
        step["title"] = change.title
        values = change.model_dump(mode="json")
        for f in _STEP_FIELDS:
            if values.get(f) is None:
                step.pop(f, None)
            else:
                step[f] = values[f]
    return out


def edit_errors(recipe: dict[str, Any], edits: Edits | dict[str, Any]) -> list[str]:
    """Problems the edited recipe would have (empty = fine). Ignores problems the original already had."""
    before = set(validate_recipe(recipe).errors)
    after = validate_recipe(apply_edits(recipe, edits))
    return [err for err in after.errors if err not in before]
