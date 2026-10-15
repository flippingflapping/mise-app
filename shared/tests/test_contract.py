"""The contract: fixtures validate against the JSON Schema and the Pydantic models agree."""

import copy
import json

import pytest

from mise_shared.config import load_config
from mise_shared.models import Recipe
from mise_shared.paths import FIXTURES_DIR
from mise_shared.util import sha256_text, slugify
from mise_shared.validation import load_schema, placeholders, validate_recipe

EXPECTED = sorted((FIXTURES_DIR / "expected").glob("*.json"))


def _load(p):
    return json.loads(p.read_text(encoding="utf-8"))


def test_there_are_fixtures():
    assert EXPECTED, "no fixtures found"


@pytest.mark.parametrize("path", EXPECTED, ids=lambda p: p.name)
def test_fixture_is_schema_and_semantically_valid(path):
    res = validate_recipe(_load(path))
    assert res.ok, str(res)
    assert res.warnings == []  # the reference fixture uses every ingredient


@pytest.mark.parametrize("path", EXPECTED, ids=lambda p: p.name)
def test_pydantic_round_trip_is_lossless(path):
    data = _load(path)
    model = Recipe.model_validate(data)
    assert model.to_json_dict() == data


def test_fixture_hash_matches_raw_text():
    data = _load(FIXTURES_DIR / "expected" / "sri-lankan-lamb-curry-for-kothu.json")
    raw = (FIXTURES_DIR / "raw" / "lamb-curry-for-kothu.txt").read_text(encoding="utf-8")
    assert data["source_text_sha256"] == sha256_text(raw)
    assert sha256_text(raw.replace("\n", "\r\n")) == sha256_text(raw)


def test_schema_version_and_units():
    schema = load_schema()
    assert schema["properties"]["schema_version"]["const"] == 1
    assert schema["$defs"]["unit"]["enum"][-1] is None


@pytest.fixture
def recipe():
    return _load(FIXTURES_DIR / "expected" / "sri-lankan-lamb-curry-for-kothu.json")


def test_unknown_unit_rejected(recipe):
    recipe["ingredients"][0]["unit"] = "handful"
    res = validate_recipe(recipe)
    assert not res.ok and "ingredients[0].unit" in res.errors[0]


def test_unknown_field_rejected(recipe):
    recipe["calories"] = 900
    assert not validate_recipe(recipe).ok


def test_missing_placeholder_id_is_error(recipe):
    recipe["steps"][0]["content"] += " Add {9999}."
    res = validate_recipe(recipe)
    assert any("{9999}" in e for e in res.errors)


def test_malformed_placeholder_is_error(recipe):
    recipe["steps"][0]["content"] += " Add {lamb}."
    assert any("malformed" in e for e in validate_recipe(recipe).errors)


def test_duplicate_ingredient_id_is_error(recipe):
    recipe["ingredients"][1]["id"] = "0001"
    assert any("duplicate" in e for e in validate_recipe(recipe).errors)


def test_timer_range_must_be_ordered(recipe):
    recipe["steps"][1]["timer_max_seconds"] = 10
    assert any("less than timer_seconds" in e for e in validate_recipe(recipe).errors)


def test_timer_must_be_positive(recipe):
    recipe["steps"][1]["timer_seconds"] = 0
    assert not validate_recipe(recipe).ok


def test_amount_range_must_be_ordered(recipe):
    recipe["ingredients"][20]["amount_max"] = 5
    assert any("amount_max" in e for e in validate_recipe(recipe).errors)


def test_unreferenced_ingredient_is_warning(recipe):
    recipe["ingredients"].append(
        {"id": "0027", "name": "bay leaf", "short_name": "bay leaf", "amount": 1, "unit": None}
    )
    res = validate_recipe(recipe)
    assert res.ok
    assert res.warnings and "bay leaf" in res.warnings[0][1]


def test_null_amount_and_unit_survive_dump(recipe):
    r = copy.deepcopy(recipe)
    r["ingredients"][0]["amount"] = None
    dumped = Recipe.model_validate(r).to_json_dict()
    assert dumped["ingredients"][0]["amount"] is None
    assert dumped["ingredients"][8]["unit"] is None


def test_placeholder_parsing():
    assert placeholders("Add {0001} then {0002:name} and {x}") == [
        ("{0001}", "0001", "full"),
        ("{0002:name}", "0002", "name"),
        ("{x}", None, "invalid"),
    ]


def test_slugify():
    assert slugify("Sri Lankan Lamb Curry — for Kothu!") == "sri-lankan-lamb-curry-for-kothu"
    assert slugify("Crème brûlée") == "creme-brulee"


def test_config_created_with_token(tmp_path):
    cfg = load_config(tmp_path / "config.toml")
    assert len(cfg.server.token) >= 24
    assert cfg.recipes_dir == (tmp_path / "recipes").resolve()
    again = load_config(tmp_path / "config.toml")
    assert again.server.token == cfg.server.token
    assert again.converter.model == "claude-sonnet-5-5"
