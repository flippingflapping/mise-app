"""Deterministic recipe validation: JSON Schema + semantic checks.

Used by the converter (never trust the model) and the server (never trust a hand-edited file).
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from functools import lru_cache
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker

from .paths import SCHEMA_PATH

# Anything in braces is a placeholder candidate; valid ones are {0001} or {0001:name}.
_BRACES = re.compile(r"\{([^{}]*)\}")
_VALID_PLACEHOLDER = re.compile(r"^(\d{4})(:name)?$")


@dataclass
class ValidationResult:
    errors: list[str] = field(default_factory=list)
    warnings: list[tuple[str, str]] = field(default_factory=list)  # (path, message)

    @property
    def ok(self) -> bool:
        return not self.errors

    def __str__(self) -> str:
        lines = [f"ERROR: {e}" for e in self.errors]
        lines += [f"WARNING {p}: {m}" for p, m in self.warnings]
        return "\n".join(lines) or "OK"


@lru_cache(maxsize=1)
def load_schema() -> dict[str, Any]:
    return json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))


@lru_cache(maxsize=1)
def _validator() -> Draft202012Validator:
    schema = load_schema()
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=FormatChecker())


def _path(error_path: Any) -> str:
    out = ""
    for part in error_path:
        out += f"[{part}]" if isinstance(part, int) else (f".{part}" if out else str(part))
    return out or "(root)"


def schema_errors(data: Any) -> list[str]:
    errors = sorted(_validator().iter_errors(data), key=lambda e: list(e.absolute_path))
    return [f"{_path(e.absolute_path)}: {e.message}" for e in errors]


def placeholders(content: str) -> list[tuple[str, str | None, str]]:
    """Return (raw, id_or_None, mode) for each {…} in a step's content. id is None if malformed."""
    out = []
    for m in _BRACES.finditer(content):
        inner = m.group(1).strip()
        v = _VALID_PLACEHOLDER.match(inner)
        if v:
            out.append((m.group(0), v.group(1), "name" if v.group(2) else "full"))
        else:
            out.append((m.group(0), None, "invalid"))
    return out


def semantic_check(data: dict[str, Any]) -> ValidationResult:
    """Checks the schema can't express. Assumes the basic shape is already schema-valid."""
    res = ValidationResult()
    ingredients = data.get("ingredients") or []
    steps = data.get("steps") or []

    ids = [i.get("id") for i in ingredients]
    seen: set[str] = set()
    for idx, iid in enumerate(ids):
        if iid in seen:
            res.errors.append(f"ingredients[{idx}].id: duplicate ingredient id {iid!r}")
        seen.add(iid)

    step_ids: set[str] = set()
    for idx, s in enumerate(steps):
        if s.get("id") in step_ids:
            res.errors.append(f"steps[{idx}].id: duplicate step id {s.get('id')!r}")
        step_ids.add(s.get("id"))

    for idx, ing in enumerate(ingredients):
        amt, amax = ing.get("amount"), ing.get("amount_max")
        if amax is not None:
            if amt is None:
                res.errors.append(f"ingredients[{idx}].amount_max: set but amount is null")
            elif amax < amt:
                res.errors.append(f"ingredients[{idx}].amount_max: {amax} is less than amount {amt}")

    referenced: set[str] = set()
    for idx, s in enumerate(steps):
        t_min, t_max = s.get("timer_seconds"), s.get("timer_max_seconds")
        if t_min is not None and t_min <= 0:
            res.errors.append(f"steps[{idx}].timer_seconds: must be positive")
        if t_max is not None:
            if t_min is None:
                res.errors.append(f"steps[{idx}].timer_max_seconds: set without timer_seconds")
            elif t_max < t_min:
                res.errors.append(f"steps[{idx}].timer_max_seconds: {t_max} is less than timer_seconds {t_min}")
        for raw, iid, _mode in placeholders(s.get("content", "")):
            if iid is None:
                res.errors.append(
                    f"steps[{idx}].content: malformed placeholder {raw} (use {{0001}} or {{0001:name}})"
                )
            elif iid not in seen:
                res.errors.append(f"steps[{idx}].content: placeholder {raw} references unknown ingredient")
            else:
                referenced.add(iid)

    for idx, ing in enumerate(ingredients):
        if ing.get("id") not in referenced:
            res.warnings.append(
                (f"ingredients[{idx}]", f"Ingredient \"{ing.get('name')}\" is never used in any step.")
            )

    rid = data.get("id")
    for idx, rel in enumerate(data.get("related_recipes") or []):
        if rel == rid:
            res.errors.append(f"related_recipes[{idx}]: recipe links to itself")
    return res


def validate_recipe(data: Any) -> ValidationResult:
    """Full validation: schema first; semantic checks only if the shape is sound."""
    errs = schema_errors(data)
    if errs:
        return ValidationResult(errors=errs)
    return semantic_check(data)
