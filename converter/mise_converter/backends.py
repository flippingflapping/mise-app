"""Chooses who converts recipes: the Claude API (an API key) or the Claude Code CLI (a subscription)."""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Callable

from mise_shared.config import Config

from .client import AnthropicClient, LLMClient
from .claude_code import ClaudeCodeClient, auth_status, find_claude

API = "api"
CLAUDE_CODE = "claude-code"
CHOICES = ("auto", API, CLAUDE_CODE)


class BackendUnavailable(Exception):
    """Neither way of converting is set up; the message says how to fix it."""


@dataclass
class Backend:
    kind: str
    #: For people: "Claude API (your API key)" / "Claude Code (your Claude subscription)".
    label: str
    factory: Callable[[], LLMClient]
    #: Message Batches (half price, slower) only exist on the API.
    supports_batch: bool
    #: The Claude Code CLI, for the claude-code backend.
    executable: str | None = None

    def login_problem(self) -> str | None:
        """For Claude Code: why it can't be used right now (not logged in, or logged in with an API key)."""
        if self.kind != CLAUDE_CODE or self.executable is None:
            return None
        status = auth_status(self.executable)
        if status is None:
            return None  # couldn't tell (older CLI); the conversion itself will report problems
        if not status.get("loggedIn"):
            return "Claude Code isn't logged in. Run `claude` once in a terminal and log in with your Claude account."
        return None


SETUP_HELP = (
    "To convert recipes, either\n"
    "  • use your Claude subscription: install Claude Code (https://claude.com/claude-code), run `claude` once\n"
    "    and log in, then set backend = \"claude-code\" (or leave \"auto\") under [converter] in config.toml; or\n"
    "  • use an API key: create one at https://platform.claude.com/ and set it in this terminal\n"
    "    (PowerShell: $env:ANTHROPIC_API_KEY=\"sk-ant-...\"; macOS/Linux: export ANTHROPIC_API_KEY=sk-ant-...)."
)


def resolve(cfg: Config, kind: str | None = None, environ: dict[str, str] | None = None) -> Backend:
    """The backend to use: `kind` (e.g. from --backend) or config's converter.backend; "auto" prefers an API key."""
    env = os.environ if environ is None else environ
    c = cfg.converter
    want = (kind or c.backend or "auto").lower()
    if want not in CHOICES:
        raise BackendUnavailable(f"Unknown converter backend {want!r}; use one of: {', '.join(CHOICES)}.")
    has_key = bool(env.get("ANTHROPIC_API_KEY"))
    claude = find_claude(c.claude_code.command)

    if want == API or (want == "auto" and has_key):
        if not has_key:
            raise BackendUnavailable("backend = \"api\" needs ANTHROPIC_API_KEY.\n\n" + SETUP_HELP)
        return Backend(API, "Claude API (your API key)",
                       lambda: AnthropicClient(refusal_fallback=c.refusal_fallback), supports_batch=True)
    if claude is None:
        where = f"`{c.claude_code.command}` isn't on PATH" if want == CLAUDE_CODE else "no API key is set and Claude Code isn't installed"
        raise BackendUnavailable(f"Can't convert: {where}.\n\n" + SETUP_HELP)
    return Backend(
        CLAUDE_CODE, "Claude Code (your Claude subscription)",
        lambda: ClaudeCodeClient(claude, model=c.claude_code.model or c.model, effort=c.effort,
                                 timeout=c.claude_code.timeout_seconds),
        supports_batch=False,
        executable=claude,
    )
