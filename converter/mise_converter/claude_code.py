"""Converts recipes through the Claude Code CLI (`claude -p`), so a Claude subscription can be used
instead of an API key.

Each recipe is one headless call: no tools, no saved session, our own system prompt (from a file),
the recipe text on stdin, and the JSON schema via --json-schema (the validated object comes back
in the result's `structured_output`). Retries flatten the conversation into one prompt, since each
call is independent. In print mode an API key in the environment silently wins over a
subscription login, so those variables are removed from the child's environment and the CLI uses
the Claude account you're logged in with (or CLAUDE_CODE_OAUTH_TOKEN from `claude setup-token`).
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any, Callable, Iterator

from .client import BatchState, LLMResponse
from .llm_schema import LLM_RECIPE_SCHEMA

#: Short enough to fit anywhere; the real instructions are in the system prompt file.
SCHEMA_IN_PROMPT_NOTE = (
    "\n\nReply with only the recipe JSON object (no prose, no code fences). It must match this JSON Schema:\n"
)


#: Credentials that outrank a subscription login in `claude -p` (would bill the API instead).
_API_CREDENTIAL_VARS = {
    "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_PROFILE",
    "ANTHROPIC_FEDERATION_RULE_ID", "ANTHROPIC_ORGANIZATION_ID",
}


def subscription_env(environ: dict[str, str] | None = None) -> dict[str, str]:
    env = dict(os.environ if environ is None else environ)
    return {k: v for k, v in env.items() if k not in _API_CREDENTIAL_VARS and not k.startswith("CLAUDE_CODE_USE_")}


def auth_status(executable: str, run: Callable[..., subprocess.CompletedProcess] = subprocess.run) -> dict[str, Any] | None:
    """`claude auth status` as a dict (loggedIn, authMethod…), or None if it can't be run."""
    try:
        proc = run([executable, "auth", "status"], capture_output=True, text=True, encoding="utf-8",
                   timeout=30, env=subscription_env())
        return json.loads(proc.stdout)
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError, ValueError):
        return None


def find_claude(command: str = "claude") -> str | None:
    """Full path of the Claude Code CLI, or None. Accepts a name on PATH or a path."""
    if not command:
        return None
    p = Path(command).expanduser()
    if p.is_file():
        return str(p)
    return shutil.which(command)


def _is_batch_shim(path: str) -> bool:
    """npm installs `claude.cmd` on Windows; cmd.exe mangles JSON quotes and ^ in its arguments."""
    return path.lower().endswith((".cmd", ".bat"))


def _text_of(content: Any) -> str:
    if isinstance(content, str):
        return content
    parts = []
    for b in content or []:
        if b.get("type") == "text":
            parts.append(b["text"])
        elif b.get("type") in ("thinking", "redacted_thinking"):
            continue
        else:
            raise ValueError(f"the Claude Code backend only handles text recipes (got a {b.get('type')} block)")
    return "\n\n".join(parts)


def flatten_messages(messages: list[dict[str, Any]]) -> str:
    """One prompt from a (possibly multi-turn) conversation: the retry turn needs the earlier answer."""
    if len(messages) == 1:
        return _text_of(messages[0]["content"])
    out = []
    for m in messages:
        label = "Your previous answer" if m["role"] == "assistant" else "Request"
        out.append(f"## {label}\n\n{_text_of(m['content'])}")
    return "\n\n".join(out)


def _system_text(system: Any) -> str:
    return system if isinstance(system, str) else "\n\n".join(b["text"] for b in system if b.get("type") == "text")


class ClaudeCodeClient:
    """LLMClient on top of `claude -p`. Synchronous only: there is no batch mode."""

    #: Calls run one at a time by default: subscriptions have usage limits, and each call starts a process.
    max_concurrency = 1

    def __init__(
        self,
        executable: str,
        *,
        model: str | None = None,
        effort: str | None = None,
        timeout: float = 900,
        run: Callable[..., subprocess.CompletedProcess] = subprocess.run,
    ):
        self.executable = executable
        self.model = model
        self.effort = effort
        self.timeout = timeout
        self._run = run
        self.schema_inline = not _is_batch_shim(executable)

    def command(self, system_file: str, params: dict[str, Any]) -> list[str]:
        cmd = [
            self.executable, "-p",
            "--output-format", "json",
            "--tools", "",
            "--no-session-persistence",
            "--safe-mode",  # no CLAUDE.md, hooks, plugins or MCP servers; login still works (unlike --bare)
            "--system-prompt-file", system_file,
        ]
        model = self.model or params.get("model")
        if model:
            cmd += ["--model", model]
        effort = self.effort or (params.get("output_config") or {}).get("effort")
        if effort:
            cmd += ["--effort", effort]
        if self.schema_inline:
            cmd += ["--json-schema", json.dumps(LLM_RECIPE_SCHEMA, separators=(",", ":"))]
        return cmd

    def create(self, params: dict[str, Any]) -> LLMResponse:
        try:
            prompt = flatten_messages(params["messages"])
        except ValueError as e:
            return LLMResponse(None, None, error=str(e))
        if not self.schema_inline:
            prompt += SCHEMA_IN_PROMPT_NOTE + json.dumps(LLM_RECIPE_SCHEMA, separators=(",", ":"))
        env = subscription_env()
        with tempfile.TemporaryDirectory(prefix="mise-claude-") as tmp:
            system_file = os.path.join(tmp, "system.md")
            Path(system_file).write_text(_system_text(params["system"]), encoding="utf-8")
            try:
                proc = self._run(
                    self.command(system_file, params), input=prompt, capture_output=True, text=True,
                    encoding="utf-8", timeout=self.timeout, env=env, cwd=tmp,
                )
            except FileNotFoundError:
                return LLMResponse(None, None, error=f"Claude Code CLI not found at {self.executable}")
            except subprocess.TimeoutExpired:
                return LLMResponse(None, None, error=f"Claude Code took longer than {int(self.timeout)} s")
        return parse_result(proc.stdout, proc.stderr, proc.returncode)

    def batch_create(self, requests: list[dict[str, Any]]) -> str:
        raise RuntimeError("batch mode needs the API; the Claude Code backend converts one recipe at a time")

    def batch_state(self, batch_id: str) -> BatchState:
        raise RuntimeError("batch mode needs the API")

    def batch_results(self, batch_id: str) -> Iterator[tuple[str, LLMResponse]]:
        raise RuntimeError("batch mode needs the API")


def parse_result(stdout: str, stderr: str, returncode: int) -> LLMResponse:
    """Turns `claude -p --output-format json` output into an LLMResponse."""
    try:
        data = json.loads(stdout.strip().splitlines()[-1]) if stdout.strip() else None
    except (json.JSONDecodeError, IndexError):
        data = None
    if not isinstance(data, dict):
        detail = (stderr or stdout or "").strip().splitlines()
        hint = " ".join(detail[-3:])[:400] or f"exit code {returncode}"
        if "login" in hint.lower() or "logged in" in hint.lower():
            hint += " (run `claude` once in a terminal and log in with your Claude account)"
        return LLMResponse(None, None, error=f"Claude Code failed: {hint}")
    usage = {k: v for k, v in (data.get("usage") or {}).items() if isinstance(v, int)}
    if data.get("is_error") or data.get("subtype") not in (None, "success"):
        why = data.get("result") or ", ".join(map(str, data.get("errors") or [])) or data.get("subtype") or "error"
        return LLMResponse(None, None, usage=usage, error=f"Claude Code: {str(why)[:400]}")
    structured = data.get("structured_output")
    text = json.dumps(structured, ensure_ascii=False) if structured is not None else data.get("result")
    stop = data.get("stop_reason")
    if stop == "tool_use" and structured is not None:
        stop = "end_turn"  # structured output is delivered through an internal tool call
    return LLMResponse(text=text, stop_reason=stop or "end_turn", content=[{"type": "text", "text": text or ""}], usage=usage)
