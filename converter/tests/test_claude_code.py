import json
import subprocess

import pytest

from conftest import fixture_llm_output
from mise_converter import backends
from mise_converter.claude_code import ClaudeCodeClient, flatten_messages, parse_result, subscription_env
from mise_converter.prompt import request_params


def cli_output(structured=None, **extra) -> str:
    data = {"type": "result", "subtype": "success", "is_error": False, "result": json.dumps(structured),
            "structured_output": structured, "stop_reason": "tool_use", "usage": {"input_tokens": 5, "output_tokens": 9}}
    data.update(extra)
    return json.dumps(data)


class FakeRun:
    """Stands in for subprocess.run; records each call and replies with scripted stdout."""

    def __init__(self, *outputs, returncode=0):
        self.outputs = list(outputs)
        self.calls = []
        self.returncode = returncode

    def __call__(self, cmd, **kw):
        system_file = cmd[cmd.index("--system-prompt-file") + 1]
        with open(system_file, encoding="utf-8") as f:
            system = f.read()
        self.calls.append({"cmd": cmd, "input": kw["input"], "env": kw["env"], "system": system, "cwd": kw["cwd"]})
        return subprocess.CompletedProcess(cmd, self.returncode, stdout=self.outputs.pop(0), stderr="")


def test_conversion_through_claude_code_end_to_end(make_converter, monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-ant-secret")
    run = FakeRun(cli_output(fixture_llm_output()))
    conv = make_converter(ClaudeCodeClient("/usr/bin/claude", model="sonnet", effort="medium", run=run))
    docs, _ = conv.plan()
    report = conv.run_sync(docs)
    assert report.failed == [] and len(report.converted + report.needs_review) == 1
    call = run.calls[0]
    cmd = call["cmd"]
    assert cmd[:2] == ["/usr/bin/claude", "-p"]
    assert cmd[cmd.index("--tools") + 1] == ""
    assert "--safe-mode" in cmd and "--no-session-persistence" in cmd and "--bare" not in cmd
    assert cmd[cmd.index("--model") + 1] == "sonnet"
    assert json.loads(cmd[cmd.index("--json-schema") + 1])["type"] == "object"
    assert "<recipe_text>" in call["input"] and "lamb" in call["input"].lower()
    assert "Mise" in call["system"] or len(call["system"]) > 5000  # our system prompt, from a file
    assert "ANTHROPIC_API_KEY" not in call["env"]  # the subscription is used, never the API key


def test_retry_sends_the_previous_answer_and_the_errors(make_converter):
    bad = fixture_llm_output()
    bad["ingredients"] = []
    run = FakeRun(cli_output(bad), cli_output(fixture_llm_output()))
    conv = make_converter(ClaudeCodeClient("claude", run=run))
    docs, _ = conv.plan()
    report = conv.run_sync(docs)
    assert report.failed == []
    second = run.calls[1]["input"]
    assert "## Your previous answer" in second and "failed validation" in second


def test_windows_cmd_shim_puts_the_schema_in_the_prompt():
    run = FakeRun(cli_output({"title": "x"}))
    client = ClaudeCodeClient(r"C:\\Users\\me\\AppData\\Roaming\\npm\\claude.CMD", run=run)
    client.create({"system": "sys", "messages": [{"role": "user", "content": "recipe"}], "model": "m"})
    cmd = run.calls[0]["cmd"]
    assert "--json-schema" not in cmd
    assert "must match this JSON Schema" in run.calls[0]["input"]


def test_errors_are_reported_plainly():
    limit = parse_result(json.dumps({"type": "result", "subtype": "success", "is_error": True,
                                     "result": "You've hit your session limit · resets 3:45pm"}), "", 1)
    assert "session limit" in limit.error
    login = parse_result("", "Not logged in · Please run /login", 1)
    assert "log in with your Claude account" in login.error
    ok = parse_result(cli_output({"a": 1}), "", 0)
    assert ok.error is None and json.loads(ok.text) == {"a": 1} and ok.stop_reason == "end_turn"
    plain = parse_result(json.dumps({"subtype": "success", "is_error": False, "result": '{"a": 2}', "stop_reason": "end_turn"}), "", 0)
    assert json.loads(plain.text) == {"a": 2}


def test_images_are_refused_and_history_is_flattened():
    client = ClaudeCodeClient("claude", run=FakeRun())
    r = client.create({"system": "s", "messages": [{"role": "user", "content": [{"type": "image", "source": {}}]}]})
    assert "only handles text" in r.error
    text = flatten_messages([
        {"role": "user", "content": [{"type": "text", "text": "R"}]},
        {"role": "assistant", "content": [{"type": "thinking", "thinking": "hm"}, {"type": "text", "text": "{}"}]},
        {"role": "user", "content": [{"type": "text", "text": "fix"}]},
    ])
    assert text.index("R") < text.index("## Your previous answer") < text.index("fix") and "hm" not in text


def test_subscription_env_drops_api_credentials():
    env = subscription_env({"PATH": "/bin", "ANTHROPIC_API_KEY": "k", "ANTHROPIC_AUTH_TOKEN": "t",
                            "CLAUDE_CODE_USE_BEDROCK": "1", "CLAUDE_CODE_OAUTH_TOKEN": "sub"})
    assert env == {"PATH": "/bin", "CLAUDE_CODE_OAUTH_TOKEN": "sub"}


def test_backend_choice(workspace, monkeypatch):
    monkeypatch.setattr(backends, "find_claude", lambda command: "/usr/bin/claude")
    assert backends.resolve(workspace, environ={"ANTHROPIC_API_KEY": "k"}).kind == "api"  # auto prefers a key
    assert backends.resolve(workspace, environ={}).kind == "claude-code"
    assert backends.resolve(workspace, "claude-code", environ={"ANTHROPIC_API_KEY": "k"}).kind == "claude-code"
    with pytest.raises(backends.BackendUnavailable, match="needs ANTHROPIC_API_KEY"):
        backends.resolve(workspace, "api", environ={})
    monkeypatch.setattr(backends, "find_claude", lambda command: None)
    with pytest.raises(backends.BackendUnavailable, match="Claude Code"):
        backends.resolve(workspace, environ={})
    assert workspace.converter.backend == "auto" and workspace.converter.claude_code.command == "claude"
