"""Opt-in: converts the fixture with the real API. Skipped unless ANTHROPIC_API_KEY is set."""

import json
import os

import pytest

from mise_converter.client import AnthropicClient
from mise_converter.engine import Converter
from mise_shared.validation import validate_recipe

pytestmark = [
    pytest.mark.live,
    pytest.mark.skipif(not os.environ.get("ANTHROPIC_API_KEY"), reason="ANTHROPIC_API_KEY not set"),
]


def test_live_fixture_conversion(workspace):
    conv = Converter(workspace, lambda: AnthropicClient(refusal_fallback=workspace.converter.refusal_fallback))
    docs, _ = conv.plan()
    report = conv.run_sync(docs)
    assert not report.failed, conv.manifest.as_dict()
    rid = (report.converted + report.needs_review)[0]
    recipe = json.loads((conv.out_dir / f"{rid}.json").read_text(encoding="utf-8"))
    assert validate_recipe(recipe).ok
    names = " ".join(i["name"].lower() for i in recipe["ingredients"])
    assert "lamb" in names and "cardamom" in names
    assert recipe["advance_prep"], "overnight marinade should be advance prep"
    assert any("cumin" in n.lower() for n in recipe["cook_notes"])
