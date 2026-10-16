"""The schema the model fills in, and the mapping to/from the canonical recipe schema.

Structured outputs cap a strict schema at 24 optional and 16 union-typed properties (checked on
docs.claude.com), and the canonical schema has more optional fields than that. So the model gets a
flatter variant: every property is required, "" or 0 mean "absent", only `amount` is a union
(number | null), and `unit` / `heat` are enums that include null.

`normalize()` maps the model's output back to the canonical shape; the result is then validated
deterministically against the real schema (see postprocess.py).
"""

from __future__ import annotations

import copy
from typing import Any

UNITS = ["g", "kg", "ml", "l", "tsp", "tbsp", "cup", "fl_oz", "oz", "lb", "pinch", None]
HEATS = ["low", "medium_low", "medium", "medium_high", "high", None]
SOURCE_TYPES = ["book", "web", "family", "other"]


def _obj(props: dict[str, Any]) -> dict[str, Any]:
    return {"type": "object", "properties": props, "required": list(props), "additionalProperties": False}


_STR = {"type": "string"}
_INT = {"type": "integer"}
_NUM = {"type": "number"}
_BOOL = {"type": "boolean"}
_STRS = {"type": "array", "items": _STR}

LLM_RECIPE_SCHEMA: dict[str, Any] = _obj(
    {
        "id": {"type": "string", "pattern": "^[a-z0-9]+(-[a-z0-9]+)*$"},
        "title": _STR,
        "description": _STR,
        "yield": _obj({"base_servings": _NUM, "label": _STR}),
        "times": _obj({"prep_minutes": _INT, "cook_minutes": _INT, "total_minutes": _INT}),
        "advance_prep": _STRS,
        "tags": _STRS,
        "ingredients": {
            "type": "array",
            "items": _obj(
                {
                    "id": {"type": "string", "pattern": "^[0-9]{4}$"},
                    "group": _STR,
                    "name": _STR,
                    "short_name": _STR,
                    "prep": _STR,
                    "amount": {"anyOf": [_NUM, {"type": "null"}]},
                    "amount_max": _NUM,
                    "unit": {"enum": UNITS},
                    "optional": _BOOL,
                    "substitutes": _STR,
                    "scales": _BOOL,
                }
            ),
        },
        "steps": {
            "type": "array",
            "items": _obj(
                {
                    "id": _STR,
                    "title": _STR,
                    "content": _STR,
                    "timer_seconds": _INT,
                    "timer_max_seconds": _INT,
                    "heat": {"enum": HEATS},
                }
            ),
        },
        "notes": _STR,
        "cook_notes": _STRS,
        "related_recipes": _STRS,
        "source": _obj(
            {"type": {"enum": SOURCE_TYPES}, "title": _STR, "author": _STR, "page": _STR, "url": _STR}
        ),
        "review_flags": {"type": "array", "items": _obj({"path": _STR, "message": _STR})},
    }
)

# Canonical fields the converter's code owns; the model never sees or writes them.
SYSTEM_FIELDS = ("schema_version", "source_text_sha256", "status", "created_at", "updated_at", "image", "user")


def _blank(v: Any) -> bool:
    return v is None or (isinstance(v, str) and not v.strip())


def _clean_str(v: Any) -> str | None:
    return None if _blank(v) else str(v).strip()


def _positive(v: Any) -> Any:
    return v if isinstance(v, (int, float)) and not isinstance(v, bool) and v > 0 else None


def normalize(llm: dict[str, Any]) -> dict[str, Any]:
    """Model output (LLM shape) → canonical recipe fields (without system fields)."""
    out: dict[str, Any] = {}
    out["id"] = llm.get("id", "")
    out["title"] = (llm.get("title") or "").strip()
    if d := _clean_str(llm.get("description")):
        out["description"] = d

    y = llm.get("yield") or {}
    out["yield"] = {"base_servings": y.get("base_servings")}
    if label := _clean_str(y.get("label")):
        out["yield"]["label"] = label

    times = {k: v for k, v in (llm.get("times") or {}).items() if _positive(v)}
    if times:
        out["times"] = times

    for key in ("advance_prep", "tags", "cook_notes", "related_recipes"):
        vals = [s.strip() for s in (llm.get(key) or []) if not _blank(s)]
        if key == "tags":
            vals = list(dict.fromkeys(v.lower() for v in vals))
        out[key] = vals

    ings = []
    for i in llm.get("ingredients") or []:
        ing: dict[str, Any] = {"id": i.get("id")}
        if g := _clean_str(i.get("group")):
            ing["group"] = g
        ing["name"] = (i.get("name") or "").strip()
        ing["short_name"] = (i.get("short_name") or "").strip() or ing["name"]
        if p := _clean_str(i.get("prep")):
            ing["prep"] = p
        amount = i.get("amount")
        ing["amount"] = amount if _positive(amount) else None
        amax = _positive(i.get("amount_max"))
        if amax is not None and ing["amount"] is not None and amax != ing["amount"]:
            ing["amount_max"] = amax
        ing["unit"] = i.get("unit")
        if i.get("optional") is True:
            ing["optional"] = True
        if s := _clean_str(i.get("substitutes")):
            ing["substitutes"] = s
        if i.get("scales") is False:
            ing["scales"] = False
        ings.append(ing)
    out["ingredients"] = ings

    steps = []
    for s in llm.get("steps") or []:
        step: dict[str, Any] = {
            "id": (s.get("id") or "").strip(),
            "title": (s.get("title") or "").strip(),
            "content": (s.get("content") or "").strip(),
        }
        if t := _positive(s.get("timer_seconds")):
            step["timer_seconds"] = int(t)
            if (tm := _positive(s.get("timer_max_seconds"))) and tm != t:
                step["timer_max_seconds"] = int(tm)
        elif _positive(s.get("timer_max_seconds")):
            # A max without a min is meaningless; keep it so validation reports it.
            step["timer_max_seconds"] = int(s["timer_max_seconds"])
        if s.get("heat"):
            step["heat"] = s["heat"]
        steps.append(step)
    out["steps"] = steps

    if n := _clean_str(llm.get("notes")):
        out["notes"] = n

    src = llm.get("source") or {}
    source: dict[str, Any] = {"type": src.get("type") or "other"}
    for k in ("title", "author", "page", "url"):
        if v := _clean_str(src.get(k)):
            source[k] = v
    out["source"] = source

    out["review_flags"] = [
        {"path": (f.get("path") or "").strip(), "message": f.get("message").strip()}
        for f in llm.get("review_flags") or []
        if not _blank(f.get("message"))
    ]
    return out


def denormalize(recipe: dict[str, Any]) -> dict[str, Any]:
    """Canonical recipe → LLM shape. Used for the prompt's worked example and for tests."""
    r = copy.deepcopy(recipe)
    times = r.get("times") or {}
    src = r.get("source") or {}
    return {
        "id": r["id"],
        "title": r["title"],
        "description": r.get("description", ""),
        "yield": {"base_servings": r["yield"]["base_servings"], "label": r["yield"].get("label", "")},
        "times": {k: times.get(k, 0) for k in ("prep_minutes", "cook_minutes", "total_minutes")},
        "advance_prep": r.get("advance_prep", []),
        "tags": r.get("tags", []),
        "ingredients": [
            {
                "id": i["id"],
                "group": i.get("group", ""),
                "name": i["name"],
                "short_name": i["short_name"],
                "prep": i.get("prep", ""),
                "amount": i["amount"],
                "amount_max": i.get("amount_max", 0),
                "unit": i["unit"],
                "optional": i.get("optional", False),
                "substitutes": i.get("substitutes", ""),
                "scales": i.get("scales", True),
            }
            for i in r["ingredients"]
        ],
        "steps": [
            {
                "id": s["id"],
                "title": s["title"],
                "content": s["content"],
                "timer_seconds": s.get("timer_seconds", 0),
                "timer_max_seconds": s.get("timer_max_seconds", 0),
                "heat": s.get("heat"),
            }
            for s in r["steps"]
        ],
        "notes": r.get("notes", ""),
        "cook_notes": r.get("cook_notes", []),
        "related_recipes": r.get("related_recipes", []),
        "source": {"type": src.get("type", "other"), **{k: src.get(k, "") for k in ("title", "author", "page", "url")}},
        "review_flags": r.get("review_flags", []),
    }


def schema_stats(schema: dict[str, Any] = LLM_RECIPE_SCHEMA) -> dict[str, int]:
    """Count optional and union-typed properties (structured-output limits: 24 and 16)."""
    stats = {"optional": 0, "union": 0}

    def walk(node: Any) -> None:
        if not isinstance(node, dict):
            return
        props = node.get("properties")
        if isinstance(props, dict):
            req = set(node.get("required", []))
            stats["optional"] += sum(1 for k in props if k not in req)
            for p in props.values():
                if "anyOf" in p or isinstance(p.get("type"), list):
                    stats["union"] += 1
                walk(p)
        if "items" in node:
            walk(node["items"])
        for alt in node.get("anyOf", []):
            walk(alt)

    walk(schema)
    return stats
