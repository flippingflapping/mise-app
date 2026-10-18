"""config.toml handling, shared by the converter and the server.

Lookup order: --config flag, MISE_CONFIG env var, ./config.toml in the repo root.
The file is created with defaults (and a fresh access token) the first time it is needed.
Relative paths inside it are resolved against the config file's own folder.
"""

from __future__ import annotations

import os
import secrets
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import tomli_w

if sys.version_info >= (3, 11):
    import tomllib
else:  # pragma: no cover
    import tomli as tomllib

from .paths import REPO_ROOT

DEFAULT_CONFIG_PATH = REPO_ROOT / "config.toml"

DEFAULTS: dict[str, Any] = {
    "library": {"recipes_dir": "recipes"},
    "server": {"host": "0.0.0.0", "port": 8765, "token": "", "advertise_host": ""},
    "converter": {
        "inbox_dir": "inbox",
        "model": "claude-sonnet-5-5",
        "max_tokens": 32000,
        "effort": "medium",
        "concurrency": 3,
        "batch_threshold": 5,
        "refusal_fallback": True,
        "poll_seconds": 30,
        "pricing": {
            # USD per million tokens for the model above; check https://docs.claude.com pricing.
            "input_per_mtok": 2.0,
            "output_per_mtok": 10.0,
            "cache_write_multiplier": 1.25,
            "cache_read_multiplier": 0.05,
            "batch_discount": 0.5,
            "est_output_tokens_per_recipe": 6000,
        },
    },
}


def _merge(base: dict[str, Any], over: dict[str, Any]) -> dict[str, Any]:
    out = dict(base)
    for k, v in over.items():
        out[k] = _merge(base[k], v) if isinstance(v, dict) and isinstance(base.get(k), dict) else v
    return out


@dataclass
class Pricing:
    input_per_mtok: float
    output_per_mtok: float
    cache_write_multiplier: float
    cache_read_multiplier: float
    batch_discount: float
    est_output_tokens_per_recipe: int


@dataclass
class ConverterConfig:
    inbox_dir: Path
    model: str
    max_tokens: int
    effort: str
    concurrency: int
    batch_threshold: int
    refusal_fallback: bool
    poll_seconds: int
    pricing: Pricing


@dataclass
class ServerConfig:
    host: str
    port: int
    token: str
    #: The address put in the pairing QR code. Empty = auto-detect the PC's LAN address.
    advertise_host: str = ""


@dataclass
class Config:
    path: Path
    recipes_dir: Path
    server: ServerConfig
    converter: ConverterConfig
    raw: dict[str, Any] = field(repr=False, default_factory=dict)

    @property
    def state_dir(self) -> Path:
        """Converter bookkeeping lives in a dot-folder the server ignores."""
        return self.recipes_dir / ".mise"


def resolve_config_path(explicit: str | Path | None = None) -> Path:
    if explicit:
        return Path(explicit).expanduser().resolve()
    env = os.environ.get("MISE_CONFIG")
    if env:
        return Path(env).expanduser().resolve()
    return DEFAULT_CONFIG_PATH


def load_config(explicit: str | Path | None = None, *, create: bool = True) -> Config:
    path = resolve_config_path(explicit)
    user: dict[str, Any] = {}
    if path.exists():
        user = tomllib.loads(path.read_text(encoding="utf-8"))
    raw = _merge(DEFAULTS, user)

    if not raw["server"].get("token"):
        raw["server"]["token"] = secrets.token_urlsafe(24)
        user.setdefault("server", {})["token"] = raw["server"]["token"]
        if create:
            _write_user_config(path, user)
    elif create and not path.exists():
        _write_user_config(path, user)

    base = path.parent

    def p(v: str) -> Path:
        q = Path(v).expanduser()
        return (q if q.is_absolute() else base / q).resolve()

    c, s, pr = raw["converter"], raw["server"], raw["converter"]["pricing"]
    return Config(
        path=path,
        recipes_dir=p(raw["library"]["recipes_dir"]),
        server=ServerConfig(host=s["host"], port=int(s["port"]), token=s["token"], advertise_host=str(s.get("advertise_host") or "")),
        converter=ConverterConfig(
            inbox_dir=p(c["inbox_dir"]),
            model=c["model"],
            max_tokens=int(c["max_tokens"]),
            effort=c["effort"],
            concurrency=int(c["concurrency"]),
            batch_threshold=int(c["batch_threshold"]),
            refusal_fallback=bool(c["refusal_fallback"]),
            poll_seconds=int(c["poll_seconds"]),
            pricing=Pricing(**pr),
        ),
        raw=raw,
    )


def _write_user_config(path: Path, user: dict[str, Any]) -> None:
    """Write the user's settings, with every default spelled out so the file is self-documenting."""
    full = _merge(DEFAULTS, user)
    path.parent.mkdir(parents=True, exist_ok=True)
    header = (
        "# Mise configuration. Relative paths are relative to this file.\n"
        "# server.token is the access token the phone uses; delete it to generate a new one.\n\n"
    )
    path.write_text(header + tomli_w.dumps(full), encoding="utf-8")
