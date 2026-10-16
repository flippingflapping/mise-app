import json

from mise_converter.cli import main


def test_dry_run_needs_no_key(workspace, monkeypatch, capsys):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    rc = main(["--config", str(workspace.path), "run", "--dry-run"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "lamb-curry-for-kothu.txt" in out and "roughly $" in out
    reqs = json.loads((workspace.recipes_dir / ".mise" / "dry-run-requests.json").read_text(encoding="utf-8"))
    assert reqs[0]["model"] == "claude-sonnet-5-5"


def test_run_without_key_exits_cleanly(workspace, monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    try:
        main(["--config", str(workspace.path), "run", "--yes"])
    except SystemExit as e:
        assert "ANTHROPIC_API_KEY" in str(e.code)
    else:
        raise AssertionError("expected SystemExit")


def test_status_lists_pending(workspace, capsys):
    assert main(["--config", str(workspace.path), "status"]) == 0
    out = capsys.readouterr().out
    assert "lamb-curry-for-kothu.txt" in out and "pending" in out
