import json

from mise_shared.edits import apply_edits, edit_errors
from mise_shared.models import Annotations, Recipe
from mise_shared.paths import FIXTURES_DIR
from mise_shared.validation import schema_errors, validate_recipe

RECIPE = json.loads((FIXTURES_DIR / "expected" / "sri-lankan-lamb-curry-for-kothu.json").read_text(encoding="utf-8"))

EDITS = {
    "title": "Lamb curry",
    "yield": {"base_servings": 4, "label": "people"},
    "ingredients": {
        "0009": {"name": "cinnamon sticks, 9cm (3½in)", "amount": 2},  # the review flag: 2 sticks, not 1
        "0002": {"name": "minced ginger", "amount": 1, "amount_max": 2, "unit": "tsp", "prep": "heaped"},
    },
    "steps": {"s2": {"title": "Brown the lamb well", "timer_seconds": 360, "timer_max_seconds": 480}},
    "checked_flags": ["ingredients[8].amount"],
}


def test_edits_apply_on_a_copy_and_stay_valid():
    out = apply_edits(RECIPE, EDITS)
    assert RECIPE["title"] != "Lamb curry"  # original untouched
    assert out["title"] == "Lamb curry" and out["yield"] == {"base_servings": 4, "label": "people"}
    cinnamon = next(i for i in out["ingredients"] if i["id"] == "0009")
    assert cinnamon["amount"] == 2 and cinnamon["unit"] is None and cinnamon["name"].startswith("cinnamon sticks")
    ginger = next(i for i in out["ingredients"] if i["id"] == "0002")
    assert (ginger["amount"], ginger["amount_max"], ginger["prep"]) == (1, 2, "heaped")
    s2 = next(s for s in out["steps"] if s["id"] == "s2")
    assert (s2["title"], s2["timer_seconds"], s2["timer_max_seconds"]) == ("Brown the lamb well", 360, 480)
    assert validate_recipe(out).ok
    assert edit_errors(RECIPE, EDITS) == []


def test_clearing_a_range_and_unknown_ids():
    out = apply_edits(RECIPE, {"ingredients": {"0002": {"name": "minced ginger", "amount": 1, "unit": "tsp"}, "9999": {"name": "x"}}})
    ginger = next(i for i in out["ingredients"] if i["id"] == "0002")
    assert "amount_max" not in ginger and "prep" not in ginger
    assert len(out["ingredients"]) == len(RECIPE["ingredients"])


def test_bad_edits_are_reported():
    bad = {"ingredients": {"0002": {"name": "ginger", "amount": 3, "amount_max": 1, "unit": "tsp"}}}
    errors = edit_errors(RECIPE, bad)
    assert errors and "less than amount" in errors[0]


def test_edits_in_user_data_match_schema_and_models():
    served = dict(RECIPE, user={"reviewed": False, "edits": EDITS})
    assert schema_errors(served) == []
    assert Recipe.model_validate(served).user.edits.ingredients["0009"].amount == 2
    a = Annotations.model_validate({"edits": EDITS})
    assert a.model_dump(mode="json", by_alias=True, exclude_none=True)["edits"]["yield"]["base_servings"] == 4


def test_add_and_remove_ingredients_and_reword_steps():
    s2 = next(s for s in RECIPE["steps"] if s["id"] == "s2")
    edits = {
        "removed_ingredients": ["0001"],
        "added_ingredients": [{"id": "9001", "name": "chicken thighs", "short_name": "chicken", "amount": 600, "unit": "g"}],
        "steps": {"s2": {"title": "Brown the chicken", "content": "Heat {0007}, add {9001} and brown it."}},
    }
    # The lamb is still used by other steps, so this alone breaks the recipe…
    assert any("unknown ingredient" in e for e in edit_errors(RECIPE, edits))
    # …until every step that mentions it is reworded (the app does this when removing).
    for step in RECIPE["steps"]:
        if "{0001" in step["content"] and step["id"] != "s2":
            edits["steps"][step["id"]] = {"title": step["title"], "content": step["content"].replace("{0001}", "lamb").replace("{0001:name}", "lamb")}
    assert edit_errors(RECIPE, edits) == []
    out = apply_edits(RECIPE, edits)
    assert [i["id"] for i in out["ingredients"]][-1] == "9001" and "0001" not in [i["id"] for i in out["ingredients"]]
    assert next(s for s in out["steps"] if s["id"] == "s2")["content"] == "Heat {0007}, add {9001} and brown it."
    assert s2["content"] != "Heat {0007}, add {9001} and brown it."  # original untouched
