"""Recipes added from the phone: saved to the inbox, then converted in the background.

The phone never holds an API key, so conversion happens here — only if this PC can convert: with
ANTHROPIC_API_KEY in the server's environment, or Claude Code logged in with a Claude subscription
(see converter.backend in config.toml). Otherwise the text just waits in the inbox for
`convert run`, exactly like a file you dropped there yourself.
"""

from __future__ import annotations

import logging
import queue
import re
import threading
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime
from typing import Any, Callable

from mise_shared.config import Config
from mise_shared.util import now_iso, slugify, write_text_atomic

from .library import RecipeLibrary

log = logging.getLogger("mise.imports")

MAX_TEXT = 100_000
PHONE_FOLDER = "from-phone"

# Job statuses
SAVED = "saved"  # in the inbox; no API key on this server, so `convert run` will pick it up
QUEUED = "queued"
CONVERTING = "converting"
DONE = "done"
NEEDS_REVIEW = "needs_review"
FAILED = "failed"


@dataclass
class ImportJob:
    id: str
    filename: str  # relative to the inbox ("" for a request)
    status: str
    created_at: str = field(default_factory=now_iso)
    recipe_id: str | None = None
    error: str | None = None
    #: "import" (pasted text, converted) or "request" (a recipe written from a few words).
    kind: str = "import"
    #: For a request: what was asked.
    request: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def compose_text(text: str, title: str | None, source: str | None) -> str:
    """The inbox file: optional title and source lines first, then the pasted text as-is."""
    parts = []
    if title and title.strip():
        parts.append(title.strip())
    if source and source.strip():
        parts.append(f"Source: {source.strip()}")
    parts.append(text.strip())
    return "\n\n".join(parts) + "\n"


class ImportQueue:
    """Saves incoming recipes and converts them one at a time on a worker thread."""

    def __init__(
        self,
        cfg: Config,
        library: RecipeLibrary,
        *,
        client_factory: Callable[[], Any] | None = None,
        can_convert: bool | None = None,
    ):
        self.cfg = cfg
        self.library = library
        self.backend_label: str | None = None
        if client_factory is None and can_convert is None:
            from mise_converter.backends import BackendUnavailable, resolve

            try:
                backend = resolve(cfg)
                if problem := backend.login_problem():
                    log.warning("Can't convert recipes from the phone: %s", problem)
                else:
                    client_factory, self.backend_label = backend.factory, backend.label
            except BackendUnavailable:
                client_factory = None
        self._client_factory = client_factory
        self.can_convert = (client_factory is not None) if can_convert is None else can_convert
        self._jobs: dict[str, ImportJob] = {}
        self._lock = threading.Lock()
        self._queue: queue.Queue[str] = queue.Queue()
        self._worker: threading.Thread | None = None
        self._finder = None  # SubstituteFinder, made on first use

    # ------------------------------------------------------------------ API

    def submit(self, text: str, title: str | None = None, source: str | None = None) -> ImportJob:
        body = compose_text(text, title, source)
        first_line = next((ln for ln in body.splitlines() if ln.strip()), "recipe")
        stem = slugify(first_line)[:50].strip("-") or "recipe"
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        filename = f"{PHONE_FOLDER}/{stem}-{stamp}.txt"
        path = self.cfg.converter.inbox_dir / filename
        n = 2
        while path.exists():
            filename = f"{PHONE_FOLDER}/{stem}-{stamp}-{n}.txt"
            path = self.cfg.converter.inbox_dir / filename
            n += 1
        write_text_atomic(path, body)
        job = ImportJob(id=uuid.uuid4().hex[:12], filename=filename, status=QUEUED if self.can_convert else SAVED)
        with self._lock:
            self._jobs[job.id] = job
        log.info("Recipe from phone saved to inbox/%s", filename)
        if self.can_convert:
            self._ensure_worker()
            self._queue.put(job.id)
        return job

    def request(self, text: str) -> ImportJob:
        """Queue writing a new recipe from a request ("a quick dal for 4"). Needs a way to convert."""
        if not self.can_convert:
            raise RuntimeError("this PC can't write recipes: log in to Claude Code or set ANTHROPIC_API_KEY")
        job = ImportJob(id=uuid.uuid4().hex[:12], filename="", status=QUEUED, kind="request", request=text.strip())
        with self._lock:
            self._jobs[job.id] = job
        log.info("Recipe requested from the phone: %s", job.request[:80])
        self._ensure_worker()
        self._queue.put(job.id)
        return job

    def substitutes(self, recipe: dict[str, Any], ingredient_id: str) -> list[dict[str, Any]]:
        """Swap ideas for one ingredient (cached). Runs on the caller's thread: it's one quick call."""
        from mise_converter.substitutes import SubstituteFinder

        if self._finder is None:
            self._finder = SubstituteFinder(self.cfg.state_dir, self.cfg.converter.model)
        cached = self._finder.cached(recipe, ingredient_id)
        if cached is not None:
            return cached
        if not self.can_convert or self._client_factory is None:
            raise RuntimeError("this PC can't suggest substitutes: log in to Claude Code or set ANTHROPIC_API_KEY")
        return self._finder.find(self._client_factory(), recipe, ingredient_id)

    def get(self, job_id: str) -> ImportJob | None:
        with self._lock:
            return self._jobs.get(job_id)

    def recent(self, limit: int = 20) -> list[ImportJob]:
        with self._lock:
            return sorted(self._jobs.values(), key=lambda j: j.created_at, reverse=True)[:limit]

    def wait_idle(self, timeout: float = 10) -> None:
        """For tests: block until queued jobs are processed."""
        self._queue.join()

    # ------------------------------------------------------------------ worker

    def _ensure_worker(self) -> None:
        if self._worker is None or not self._worker.is_alive():
            self._worker = threading.Thread(target=self._run, name="mise-imports", daemon=True)
            self._worker.start()

    def _set(self, job: ImportJob, **changes: Any) -> None:
        with self._lock:
            for k, v in changes.items():
                setattr(job, k, v)

    def _run(self) -> None:
        while True:
            job_id = self._queue.get()
            job = None
            try:
                job = self.get(job_id)
                if job and job.kind == "request":
                    self._write_requested(job)
                elif job:
                    self._convert(job)
            except Exception as e:  # never let one recipe kill the worker
                log.exception("Import %s crashed", job_id)
                if job:
                    self._set(job, status=FAILED, error=str(e))
            finally:
                self._queue.task_done()

    def _write_requested(self, job: ImportJob) -> None:
        from mise_converter.generate import generate_recipe

        self._set(job, status=CONVERTING)
        result = generate_recipe(self.cfg, self._client_factory(), job.request or "", log=lambda m: log.info(m.strip()))
        if result.recipe_id:
            self.library.reload([result.recipe_id])
            self._set(job, status=NEEDS_REVIEW, recipe_id=result.recipe_id)
        else:
            self._set(job, status=FAILED, error=re.sub(r"\s+", " ", "; ".join(result.errors))[:500] or "no recipe came back")

    def _convert(self, job: ImportJob) -> None:
        from mise_converter import manifest as mf
        from mise_converter.engine import Converter

        self._set(job, status=CONVERTING)
        conv = Converter(self.cfg, self._client_factory, log=lambda m: log.info(m.strip()))
        docs, _ = conv.scan()
        doc = next((d for d in docs if d.key == job.filename), None)
        if doc is None:
            self._set(job, status=FAILED, error="the saved text disappeared from the inbox")
            return
        conv.run_sync([doc])
        entry = conv.manifest.get(job.filename)
        if entry and entry.status in mf.CONVERTED and entry.recipe_id:
            self.library.reload([entry.recipe_id])
            self._set(
                job,
                status=NEEDS_REVIEW if entry.status == mf.NEEDS_REVIEW else DONE,
                recipe_id=entry.recipe_id,
            )
        else:
            error = (entry.error if entry else None) or "conversion failed"
            self._set(job, status=FAILED, error=re.sub(r"\s+", " ", error)[:500])
