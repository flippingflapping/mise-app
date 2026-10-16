import json

from jsonschema import Draft202012Validator

from conftest import EXPECTED, fixture_llm_output
from mise_converter.llm_schema import LLM_RECIPE_SCHEMA, SYSTEM_FIELDS, normalize, schema_stats
from mise_converter.prompt import prompt_version, request_params, system_prompt
from mise_converter.inputs import SourceDocument


def test_llm_schema_within_structured_output_limits():
    stats = schema_stats()
    assert stats["optional"] <= 24
    assert stats["union"] <= 16


def test_every_object_is_closed_and_fully_required():
    def walk(node):
        if isinstance(node, dict):
            if node.get("type") == "object":
                assert node["additionalProperties"] is False
                assert set(node["required"]) == set(node["properties"])
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    walk(LLM_RECIPE_SCHEMA)


def test_fixture_in_llm_shape_validates_and_round_trips():
    llm = fixture_llm_output()
    Draft202012Validator.check_schema(LLM_RECIPE_SCHEMA)
    assert list(Draft202012Validator(LLM_RECIPE_SCHEMA).iter_errors(llm)) == []
    expected = json.loads(EXPECTED.read_text(encoding="utf-8"))
    for f in SYSTEM_FIELDS:
        expected.pop(f, None)
    expected["source"].pop("original_filename")
    assert normalize(llm) == expected


def test_normalize_drops_blank_and_zero_markers():
    llm = fixture_llm_output()
    llm["ingredients"][0]["amount_max"] = 500  # same as amount → not a range
    llm["steps"][0]["timer_seconds"] = 0
    llm["description"] = "  "
    out = normalize(llm)
    assert "amount_max" not in out["ingredients"][0]
    assert "timer_seconds" not in out["steps"][0]
    assert "description" not in out
    assert "times" not in out


def test_system_prompt_is_complete_and_stable():
    sp = system_prompt()
    assert "{{" not in sp
    assert "MAKES ENOUGH FOR ABOUT 2 PORTIONS OF KOTHU" in sp
    assert '"short_name": "cardamom pods"' in sp
    assert "Never invent quantities" in sp
    assert "prompt_version" not in sp  # the version comment is stripped
    assert sp == system_prompt()
    assert prompt_version() == "1"


def test_request_params_cache_the_system_prompt():
    doc = SourceDocument(path=EXPECTED, key="x.txt", text="1 egg", sha256="0" * 64)
    p = request_params(doc, model="claude-sonnet-5-5", max_tokens=32000, effort="medium")
    assert p["system"][0]["cache_control"] == {"type": "ephemeral"}
    assert p["output_config"]["format"]["type"] == "json_schema"
    assert p["output_config"]["effort"] == "medium"
    assert p["thinking"] == {"type": "adaptive"}
    assert "tool_choice" not in p and "fallbacks" not in p
    assert p["messages"][0]["content"][-1]["text"].endswith("</recipe_text>")
