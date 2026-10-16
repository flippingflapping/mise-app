"""manifest.json: which source became which recipe, so each recipe is converted once.

Lives in <recipes>/.mise/manifest.json. Keyed by the source path relative to the inbox.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Optional

from mise_shared.util import write_json_atomic

# Entry statuses
PENDING = "pending"
SUBMITTED = "submitted"  # in a batch that hasn't been collected yet
DONE = "done"  # converted, status ready
NEEDS_REVIEW = "needs_review"  # converted, has review flags
FAILED = "failed"

CONVERTED = {DONE, NEEDS_REVIEW}


@dataclass
class Entry:
    sha256: str
    status: str = PENDING
    recipe_id: Optional[str] = None
    batch_id: Optional[str] = None
    custom_id: Optional[str] = None
    error: Optional[str] = None
    model: Optional[str] = None
    prompt_version: Optional[str] = None
    converted_at: Optional[str] = None
    attempts: int = 0


@dataclass
class Batch:
    id: str
    submitted_at: str
    custom_ids: dict[str, str] = field(default_factory=dict)  # custom_id → source key
    model: str = ""


class Manifest:
    VERSION = 1

    def __init__(self, path: Path):
        self.path = path
        self.entries: dict[str, Entry] = {}
        self.batches: list[Batch] = []
        if path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
            self.entries = {k: Entry(**v) for k, v in data.get("entries", {}).items()}
            self.batches = [Batch(**b) for b in data.get("batches", [])]

    def save(self) -> None:
        write_json_atomic(
            self.path,
            {
                "version": self.VERSION,
                "entries": {k: asdict(v) for k, v in sorted(self.entries.items())},
                "batches": [asdict(b) for b in self.batches],
            },
        )

    def get(self, key: str) -> Entry | None:
        return self.entries.get(key)

    def needs_conversion(self, key: str, sha256: str, *, force: bool = False) -> bool:
        """New or changed sources (or everything with --force). Failures wait for `convert retry`."""
        e = self.entries.get(key)
        if force or e is None or e.sha256 != sha256:
            return True
        return e.status == PENDING

    def recipe_ids_in_use(self, *, except_key: str | None = None) -> set[str]:
        return {e.recipe_id for k, e in self.entries.items() if e.recipe_id and k != except_key}

    def open_batch(self) -> Batch | None:
        return self.batches[0] if self.batches else None

    def close_batch(self, batch_id: str) -> None:
        self.batches = [b for b in self.batches if b.id != batch_id]

    def counts(self) -> dict[str, int]:
        out: dict[str, int] = {}
        for e in self.entries.values():
            out[e.status] = out.get(e.status, 0) + 1
        return out

    def as_dict(self) -> dict[str, Any]:
        return {k: asdict(v) for k, v in self.entries.items()}
