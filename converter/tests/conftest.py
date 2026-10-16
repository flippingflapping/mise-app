import copy
import json
import shutil
from pathlib import Path

import pytest

from mise_converter.client import BatchState, LLMResponse
from mise_converter.llm_schema import SYSTEM_FIELDS, denormalize
from mise_shared.config import load_config
from mise_shared.paths import FIXTURES_DIR

RAW = FIXTURES_DIR / "raw" / "lamb-curry-for-kothu.txt"
EXPECTED = FIXTURES_DIR / "expected" / "sri-lankan-lamb-curry-for-kothu.json"


def fixture_llm_output() -> dict:
    data = json.loads(EXPECTED.read_text(encoding="utf-8"))
    for f in SYSTEM_FIELDS:
        data.pop(f, None)
    data["source"].pop("original_filename", None)
    return denormalize(data)


def response(obj, stop_reason="end_turn") -> LLMResponse:
    text = obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)
    return LLMResponse(
        text=text,
        stop_reason=stop_reason,
        content=[{"type": "thinking", "thinking": "", "signature": "sig"}, {"type": "text", "text": text}],
        usage={"input_tokens": 100, "output_tokens": 50},
    )


class FakeClient:
    """Scripted stand-in for the Anthropic API."""

    def __init__(self, responses=None, batch_states=("in_progress", "ended")):
        self.responses = list(responses or [])
        self.calls: list[dict] = []
        self.batches: dict[str, list[dict]] = {}
        self.batch_responses: dict[str, LLMResponse] = {}
        self.batch_states = list(batch_states)
        self.state_calls = 0

    def create(self, params):
        self.calls.append(copy.deepcopy(params))
        if not self.responses:
            raise AssertionError("unexpected API call")
        r = self.responses.pop(0)
        return r(params) if callable(r) else r

    def batch_create(self, requests):
        bid = f"msgbatch_{len(self.batches) + 1}"
        self.batches[bid] = copy.deepcopy(requests)
        return bid

    def batch_state(self, batch_id):
        i = min(self.state_calls, len(self.batch_states) - 1)
        self.state_calls += 1
        return BatchState(batch_id, self.batch_states[i], {"processing": 0})

    def batch_results(self, batch_id):
        for req in self.batches[batch_id]:
            yield req["custom_id"], self.batch_responses.get(req["custom_id"], response(fixture_llm_output()))


@pytest.fixture
def workspace(tmp_path):
    inbox = tmp_path / "inbox"
    inbox.mkdir()
    shutil.copy(RAW, inbox / RAW.name)
    cfg = load_config(tmp_path / "config.toml")
    return cfg


@pytest.fixture
def make_converter(workspace):
    from mise_converter.engine import Converter

    def make(client, **kw):
        logs: list[str] = []
        conv = Converter(workspace, lambda: client, log=logs.append, sleep=lambda s: None, **kw)
        conv.logs = logs
        return conv

    return make
