"""Drives AnthropicClient through the real SDK against a mock HTTP transport.

Checks what goes over the wire (structured outputs, caching, fallbacks beta) and that real
response shapes (streamed messages, batch JSONL results) are parsed correctly. No network.
"""

import json
from pathlib import Path

import anthropic
import httpx2

from conftest import fixture_llm_output
from mise_converter.client import FALLBACK_BETA, AnthropicClient
from mise_converter.inputs import SourceDocument
from mise_converter.prompt import request_params

MODEL = "claude-sonnet-5-5"


def _message(text):
    return {
        "id": "msg_1", "type": "message", "role": "assistant", "model": MODEL,
        "content": [{"type": "thinking", "thinking": "", "signature": "sig"}, {"type": "text", "text": text}],
        "stop_reason": "end_turn", "stop_sequence": None,
        "usage": {"input_tokens": 10, "output_tokens": 20, "cache_read_input_tokens": 7000,
                  "cache_creation_input_tokens": 0},
    }


def _sse(text):
    msg = _message(text)
    start = {**msg, "content": [], "stop_reason": None, "usage": {"input_tokens": 10, "output_tokens": 1}}
    events = [
        ("message_start", {"type": "message_start", "message": start}),
        ("content_block_start", {"type": "content_block_start", "index": 0,
                                 "content_block": {"type": "thinking", "thinking": "", "signature": ""}}),
        ("content_block_delta", {"type": "content_block_delta", "index": 0,
                                 "delta": {"type": "signature_delta", "signature": "sig"}}),
        ("content_block_stop", {"type": "content_block_stop", "index": 0}),
        ("content_block_start", {"type": "content_block_start", "index": 1,
                                 "content_block": {"type": "text", "text": ""}}),
        ("content_block_delta", {"type": "content_block_delta", "index": 1,
                                 "delta": {"type": "text_delta", "text": text}}),
        ("content_block_stop", {"type": "content_block_stop", "index": 1}),
        ("message_delta", {"type": "message_delta", "delta": {"stop_reason": "end_turn", "stop_sequence": None},
                           "usage": {"output_tokens": 20}}),
        ("message_stop", {"type": "message_stop"}),
    ]
    return "".join(f"event: {e}\ndata: {json.dumps(d)}\n\n" for e, d in events)


def _client(handler, **kw):
    sdk = anthropic.Anthropic(
        api_key="test-key", base_url="http://mock.local", max_retries=0,
        http_client=anthropic.DefaultHttpxClient(transport=httpx2.MockTransport(handler)),
    )
    return AnthropicClient(sdk_client=sdk, **kw)


def _params():
    doc = SourceDocument(path=Path("r.txt"), key="r.txt", text="2 eggs", sha256="0" * 64)
    return request_params(doc, model=MODEL, max_tokens=32000, effort="medium")


def test_sync_request_on_the_wire_and_parsed():
    seen = {}
    text = json.dumps(fixture_llm_output())

    def handler(request):
        seen["url"] = str(request.url)
        seen["headers"] = dict(request.headers)
        seen["body"] = json.loads(request.content)
        return httpx2.Response(200, text=_sse(text), headers={"content-type": "text/event-stream"})

    resp = _client(handler, refusal_fallback=True).create(_params())
    body = seen["body"]
    assert seen["url"].startswith("http://mock.local/v1/messages")
    assert FALLBACK_BETA in seen["headers"]["anthropic-beta"]
    assert body["fallbacks"] == "default" and body["stream"] is True
    assert body["model"] == MODEL and body["thinking"] == {"type": "adaptive"}
    assert body["output_config"]["format"]["type"] == "json_schema"
    assert body["system"][0]["cache_control"] == {"type": "ephemeral"}
    assert "tool_choice" not in body
    assert "test-key" not in json.dumps(body)
    assert resp.error is None and resp.stop_reason == "end_turn"
    assert json.loads(resp.text)["id"] == "sri-lankan-lamb-curry-for-kothu"
    assert [b["type"] for b in resp.content] == ["thinking", "text"]


def test_without_fallback_uses_plain_messages():
    seen = {}

    def handler(request):
        seen["body"] = json.loads(request.content)
        seen["beta"] = request.headers.get("anthropic-beta")
        return httpx2.Response(200, text=_sse("{}"), headers={"content-type": "text/event-stream"})

    _client(handler, refusal_fallback=False).create(_params())
    assert "fallbacks" not in seen["body"] and seen["beta"] is None


def test_api_errors_become_failures_not_crashes():
    def handler(request):
        return httpx2.Response(400, json={"type": "error", "error": {"type": "invalid_request_error", "message": "nope"}})

    resp = _client(handler).create(_params())
    assert resp.text is None and "nope" in resp.error


def test_batch_round_trip():
    text = json.dumps(fixture_llm_output())
    seen = {}
    batch = {
        "id": "msgbatch_1", "type": "message_batch", "processing_status": "ended",
        "request_counts": {"processing": 0, "succeeded": 1, "errored": 1, "canceled": 0, "expired": 0},
        "created_at": "2026-10-16T00:00:00Z", "expires_at": "2026-10-17T00:00:00Z", "ended_at": None,
        "cancel_initiated_at": None, "archived_at": None,
        "results_url": "http://mock.local/v1/messages/batches/msgbatch_1/results",
    }
    lines = [
        {"custom_id": "r0000", "result": {"type": "succeeded", "message": _message(text)}},
        {"custom_id": "r0001", "result": {"type": "errored",
                                          "error": {"type": "error", "error": {"type": "overloaded_error", "message": "busy"}}}},
    ]

    def handler(request):
        path = request.url.path
        if request.method == "POST":
            seen["create"] = json.loads(request.content)
            return httpx2.Response(200, json={**batch, "processing_status": "in_progress"})
        if path.endswith("/results"):
            return httpx2.Response(200, text="\n".join(json.dumps(l) for l in lines),
                                   headers={"content-type": "application/binary"})
        return httpx2.Response(200, json=batch)

    c = _client(handler)
    bid = c.batch_create([{"custom_id": "r0000", "params": _params()}])
    assert bid == "msgbatch_1"
    assert "fallbacks" not in seen["create"]["requests"][0]["params"]
    state = c.batch_state(bid)
    assert state.processing_status == "ended" and state.counts["succeeded"] == 1
    results = dict(c.batch_results(bid))
    assert json.loads(results["r0000"].text)["title"] == "Sri Lankan Lamb Curry for Kothu"
    assert "overloaded_error" in results["r0001"].error
