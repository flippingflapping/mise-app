"""The recipes folder as an in-memory, validated, change-aware library.

* `<id>.json` — the recipe (never rewritten here)
* `<id>.user.json` — annotations sidecar (written by `save_annotations`)
* `<id>.source.txt` — original text for "View original"
* `<id>.jpg|.jpeg|.png|.webp` — optional image

Invalid recipe files are logged and skipped; they never take the server down.
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from mise_shared.edits import edit_errors
from mise_shared.models import Annotations
from mise_shared.util import now_iso, write_json_atomic
from mise_shared.validation import validate_recipe

log = logging.getLogger("mise.library")

SLUG = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp")
_SUFFIXES = (".user.json", ".source.txt", ".json") + IMAGE_EXTS


def id_for_path(path: Path) -> str | None:
    """Map any file in the folder to the recipe id it belongs to (or None)."""
    name = path.name
    if name.startswith("."):
        return None
    for suffix in _SUFFIXES:
        if name.lower().endswith(suffix):
            rid = name[: -len(suffix)]
            return rid if SLUG.match(rid) else None
    return None


def _parse_time(s: str | None) -> datetime | None:
    if not s:
        return None
    try:
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def _iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


@dataclass
class LibraryEntry:
    id: str
    recipe: dict[str, Any]  # merged with the sidecar, as served
    etag: str  # quoted strong ETag
    updated: datetime

    def summary(self) -> dict[str, Any]:
        r = self.recipe
        return {
            "id": self.id,
            "title": r["title"],
            "tags": r.get("tags", []),
            "status": r["status"],
            "updated_at": _iso(self.updated),
            "etag": self.etag,
        }


class RecipeLibrary:
    def __init__(self, folder: Path):
        self.folder = folder.resolve()
        self._entries: dict[str, LibraryEntry] = {}
        self._invalid: dict[str, str] = {}
        self._lock = threading.RLock()

    # ------------------------------------------------------------------ loading

    def load_all(self) -> None:
        self.folder.mkdir(parents=True, exist_ok=True)
        ids = {rid for p in self.folder.iterdir() if p.is_file() and (rid := id_for_path(p))}
        with self._lock:
            self._entries.clear()
            self._invalid.clear()
        self.reload(ids)
        log.info("Loaded %d recipe(s) from %s (%d invalid)", len(self._entries), self.folder, len(self._invalid))

    def reload(self, ids: Iterable[str]) -> None:
        for rid in ids:
            entry, error = self._load(rid)
            with self._lock:
                self._invalid.pop(rid, None)
                if entry:
                    self._entries[rid] = entry
                else:
                    self._entries.pop(rid, None)
                    if error:
                        self._invalid[rid] = error
            if error:
                log.warning("Skipping %s.json: %s", rid, error)

    def _load(self, rid: str) -> tuple[LibraryEntry | None, str | None]:
        path = self.folder / f"{rid}.json"
        if not path.exists():
            return None, None
        try:
            stat = path.stat()
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as e:
            return None, f"unreadable: {e}"
        if not isinstance(data, dict):
            return None, "not a JSON object"
        if data.get("id") != rid:
            return None, f"id {data.get('id')!r} doesn't match the file name"
        result = validate_recipe(data)
        if not result.ok:
            return None, "; ".join(result.errors[:5])

        sidecar = self._read_sidecar(rid)
        merged = self._merge(data, sidecar)
        img = self.image_path(rid, merged)
        if img and "image" not in merged:
            merged["image"] = img.name

        updated = _parse_time(data.get("updated_at")) or datetime.fromtimestamp(stat.st_mtime, timezone.utc)
        if sidecar and (s_updated := _parse_time(sidecar.updated_at)) and s_updated > updated:
            updated = s_updated
        merged["updated_at"] = _iso(updated)
        if "created_at" not in merged:
            merged["created_at"] = _iso(datetime.fromtimestamp(stat.st_mtime, timezone.utc))
        body = json.dumps(merged, sort_keys=True, ensure_ascii=False).encode("utf-8")
        etag = '"' + hashlib.sha256(body).hexdigest()[:32] + '"'
        return LibraryEntry(rid, merged, etag, updated), None

    def _read_sidecar(self, rid: str) -> Annotations | None:
        path = self.folder / f"{rid}.user.json"
        if not path.exists():
            return None
        try:
            return Annotations.model_validate_json(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            log.warning("Ignoring unreadable %s: %s", path.name, e)
            return None

    @staticmethod
    def _merge(recipe: dict[str, Any], a: Annotations | None) -> dict[str, Any]:
        merged = dict(recipe)
        if a is None:
            return merged
        if a.cook_notes is not None:
            merged["cook_notes"] = list(a.cook_notes)
        if a.reviewed is True:
            merged["status"] = "ready"
        user = {
            k: v
            for k, v in {
                "favorite": a.favorite,
                "reviewed": a.reviewed,
                "last_cooked": a.last_cooked,
                "cook_log": a.cook_log,
                # Passed through, not applied: the app applies them, so it can also undo them.
                "edits": a.edits.model_dump(mode="json", by_alias=True, exclude_none=True) if a.edits else None,
                "updated_at": a.updated_at,
            }.items()
            if v is not None
        }
        if user:
            merged["user"] = user
        return merged

    # ------------------------------------------------------------------ queries

    def ids(self) -> list[str]:
        with self._lock:
            return sorted(self._entries)

    def get(self, rid: str) -> LibraryEntry | None:
        with self._lock:
            return self._entries.get(rid)

    def summaries(self, since: datetime | None = None) -> list[dict[str, Any]]:
        with self._lock:
            entries = sorted(self._entries.values(), key=lambda e: e.id)
        return [e.summary() for e in entries if since is None or e.updated > since]

    def list_etag(self) -> str:
        with self._lock:
            parts = "|".join(f"{e.id}:{e.etag}" for e in sorted(self._entries.values(), key=lambda e: e.id))
        return '"' + hashlib.sha256(parts.encode()).hexdigest()[:32] + '"'

    @property
    def invalid(self) -> dict[str, str]:
        with self._lock:
            return dict(self._invalid)

    def source_text(self, rid: str) -> str | None:
        path = self.folder / f"{rid}.source.txt"
        return path.read_text(encoding="utf-8") if path.exists() else None

    def image_path(self, rid: str, recipe: dict[str, Any] | None = None) -> Path | None:
        named = (recipe or {}).get("image")
        candidates = [named] if named else [f"{rid}{ext}" for ext in IMAGE_EXTS]
        for name in candidates:
            if not name or "/" in name or "\\" in name or name.startswith("."):
                continue
            p = self.folder / name
            if p.is_file():
                return p
        return None

    # ------------------------------------------------------------------ writes

    def save_annotations(self, rid: str, update: Annotations) -> LibraryEntry:
        """Merge the fields present in `update` into <id>.user.json. The recipe file is untouched.

        Raises ValueError if the edits would make the recipe invalid (e.g. a range that ends below
        where it starts), with the problems in the message.
        """
        with self._lock:
            if rid not in self._entries:
                raise KeyError(rid)
            if update.edits is not None:
                base = json.loads((self.folder / f"{rid}.json").read_text(encoding="utf-8"))
                if problems := edit_errors(base, update.edits):
                    raise ValueError("; ".join(problems[:5]))
            current = self._read_sidecar(rid) or Annotations()
            data = current.model_dump(mode="json", by_alias=True, exclude_none=True)
            data.update(update.model_dump(mode="json", by_alias=True, exclude_unset=True, exclude_none=True))
            if update.edits is not None and update.edits.is_empty:
                data.pop("edits", None)  # {} = undo all edits
            data["updated_at"] = now_iso()
            new = Annotations.model_validate(data)
            write_json_atomic(self.folder / f"{rid}.user.json", new.model_dump(mode="json", by_alias=True, exclude_none=True))
            self.reload([rid])
            entry = self._entries.get(rid)
            if entry is None:  # pragma: no cover - the recipe was removed concurrently
                raise KeyError(rid)
            return entry


def watch_folder(library: RecipeLibrary, stop: threading.Event) -> None:
    """Blocking: reload recipes as files change. Run in a daemon thread."""
    from watchfiles import watch

    for changes in watch(library.folder, stop_event=stop, recursive=False, raise_interrupt=False):
        ids = {rid for _, p in changes if (rid := id_for_path(Path(p)))}
        if ids:
            log.info("Change detected: %s", ", ".join(sorted(ids)))
            library.reload(ids)


def start_watcher(library: RecipeLibrary) -> tuple[threading.Thread, threading.Event]:
    stop = threading.Event()
    t = threading.Thread(target=watch_folder, args=(library, stop), name="mise-watcher", daemon=True)
    t.start()
    return t, stop
