"""The input layer: turns files in the inbox into something the model can read.

Today: .txt and .md (plain text). The registry is designed so PDFs (document blocks) and photos
(vision image blocks) can be added later by registering a reader that returns content blocks.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from mise_shared.util import normalize_text, sha256_text


class UnsupportedInput(Exception):
    pass


@dataclass
class SourceDocument:
    path: Path
    #: Key in the manifest: the path relative to the inbox, with forward slashes.
    key: str
    #: Plain text (what "View original" shows and what is hashed).
    text: str
    sha256: str
    #: Extra content blocks to send ahead of the text (e.g. an image), for future readers.
    blocks: list[dict[str, Any]] = field(default_factory=list)

    @property
    def filename(self) -> str:
        return self.path.name


Reader = Callable[[Path], tuple[str, list[dict[str, Any]]]]


def _read_text(path: Path) -> tuple[str, list[dict[str, Any]]]:
    raw = path.read_bytes()
    for enc in ("utf-8-sig", "cp1252", "latin-1"):
        try:
            return normalize_text(raw.decode(enc)), []
        except UnicodeDecodeError:
            continue
    raise UnsupportedInput(f"{path.name}: could not decode text")


def _not_yet(kind: str) -> Reader:
    def reader(path: Path) -> tuple[str, list[dict[str, Any]]]:
        raise UnsupportedInput(f"{path.name}: {kind} import is designed for but not implemented yet")

    return reader


READERS: dict[str, Reader] = {
    ".txt": _read_text,
    ".md": _read_text,
    # Future: send as a {"type": "document"} block (base64 PDF); text = extracted text.
    ".pdf": _not_yet("PDF"),
    # Future: send as {"type": "image"} blocks (vision); text = the model's transcription.
    ".jpg": _not_yet("photo"),
    ".jpeg": _not_yet("photo"),
    ".png": _not_yet("photo"),
    ".heic": _not_yet("photo"),
}
SUPPORTED_NOW = {".txt", ".md"}


def scan_inbox(inbox: Path) -> tuple[list[SourceDocument], list[str]]:
    """Return readable documents and human-readable notes about skipped files."""
    docs: list[SourceDocument] = []
    skipped: list[str] = []
    if not inbox.exists():
        return docs, [f"inbox {inbox} does not exist"]
    for path in sorted(p for p in inbox.rglob("*") if p.is_file()):
        rel = path.relative_to(inbox)
        if any(part.startswith(".") for part in rel.parts):
            continue
        reader = READERS.get(path.suffix.lower())
        if reader is None:
            skipped.append(f"{rel.as_posix()}: unsupported file type")
            continue
        try:
            text, blocks = reader(path)
        except UnsupportedInput as e:
            skipped.append(str(e))
            continue
        if not text.strip():
            skipped.append(f"{rel.as_posix()}: empty")
            continue
        docs.append(SourceDocument(path, rel.as_posix(), text, sha256_text(text), blocks))
    return docs, skipped
