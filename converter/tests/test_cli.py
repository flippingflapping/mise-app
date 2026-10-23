import json

from mise_converter.cli import main


def no_claude_code(monkeypatch):
    monkeypatch.setattr("mise_converter.backends.find_claude", lambda command: None)


def test_dry_run_needs_no_key(workspace, monkeypatch, capsys):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    no_claude_code(monkeypatch)
    rc = main(["--config", str(workspace.path), "run", "--dry-run"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "lamb-curry-for-kothu.txt" in out and "roughly $" in out
    reqs = json.loads((workspace.recipes_dir / ".mise" / "dry-run-requests.json").read_text(encoding="utf-8"))
    assert reqs[0]["model"] == "claude-sonnet-5-5"


def test_run_without_key_exits_cleanly(workspace, monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    no_claude_code(monkeypatch)
    try:
        main(["--config", str(workspace.path), "run", "--yes"])
    except SystemExit as e:
        assert "ANTHROPIC_API_KEY" in str(e.code) and "Claude Code" in str(e.code)
    else:
        raise AssertionError("expected SystemExit")


def test_status_lists_pending(workspace, capsys):
    assert main(["--config", str(workspace.path), "status"]) == 0
    out = capsys.readouterr().out
    assert "lamb-curry-for-kothu.txt" in out and "pending" in out


def test_dry_run_with_claude_code_shows_no_api_charges(workspace, monkeypatch, capsys):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-ant-should-not-be-used")
    monkeypatch.setattr("mise_converter.backends.find_claude", lambda command: "/usr/bin/claude")
    rc = main(["--config", str(workspace.path), "--backend", "claude-code", "run", "--dry-run", "--batch"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "Claude Code (your Claude subscription)" in out
    assert "no API charges" in out and "roughly $" not in out
    assert "Batch mode needs the API" in out


def test_ask_writes_a_recipe(workspace, monkeypatch, capsys):
    from conftest import FakeClient, fixture_llm_output, response
    from mise_converter import backends

    fake = FakeClient([response(fixture_llm_output())])
    monkeypatch.setattr(backends, "find_claude", lambda command: "/usr/bin/claude")
    monkeypatch.setattr(backends.Backend, "login_problem", lambda self: None)
    monkeypatch.setattr(backends, "ClaudeCodeClient", lambda *a, **k: fake)
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    assert main(["--config", str(workspace.path), "ask", "a", "lamb", "curry", "--yes"]) == 0
    out = capsys.readouterr().out
    assert "Needs review" in out
    system = fake.calls[0]["system"][0]["text"]
    assert "How writing differs from converting" in system and "# Ingredients" in system  # both guides
    assert "<request>\na lamb curry\n</request>" in fake.calls[0]["messages"][0]["content"][0]["text"]
