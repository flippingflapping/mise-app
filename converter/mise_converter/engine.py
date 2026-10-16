"""Conversion pipeline: plan → (sync | batch) → validate → retry once with errors → write."""

from __future__ import annotations

import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from mise_shared.config import Config
from mise_shared.util import now_iso, write_json_atomic, write_text_atomic

from . import manifest as mf
from .client import LLMClient, LLMResponse
from .inputs import SourceDocument, scan_inbox
from .manifest import Batch, Entry, Manifest
from .postprocess import finalize, parse_output, unique_id
from .prompt import prompt_version, request_params, retry_content, user_content

HISTORY_BLOCK_TYPES = {"text", "thinking", "redacted_thinking"}


@dataclass
class RunReport:
    converted: list[str] = field(default_factory=list)  # recipe ids
    needs_review: list[str] = field(default_factory=list)
    failed: list[str] = field(default_factory=list)  # source keys
    skipped: list[str] = field(default_factory=list)
    still_running: str | None = None  # batch id


class Converter:
    def __init__(
        self,
        cfg: Config,
        client_factory: Callable[[], LLMClient],
        *,
        inbox: Path | None = None,
        out_dir: Path | None = None,
        log: Callable[[str], None] = print,
        sleep: Callable[[float], None] = time.sleep,
    ):
        self.cfg = cfg
        self.inbox = (inbox or cfg.converter.inbox_dir).resolve()
        self.out_dir = (out_dir or cfg.recipes_dir).resolve()
        self.state_dir = self.out_dir / ".mise"
        self.manifest = Manifest(self.state_dir / "manifest.json")
        self._client_factory = client_factory
        self._client: LLMClient | None = None
        self.log = log
        self.sleep = sleep
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ planning

    @property
    def client(self) -> LLMClient:
        if self._client is None:
            self._client = self._client_factory()
        return self._client

    def scan(self) -> tuple[list[SourceDocument], list[str]]:
        return scan_inbox(self.inbox)

    def plan(self, *, force: bool = False) -> tuple[list[SourceDocument], list[str]]:
        docs, skipped = self.scan()
        open_ids = {b.id for b in self.manifest.batches}
        todo = []
        for d in docs:
            e = self.manifest.get(d.key)
            if e and e.status == mf.SUBMITTED and e.sha256 == d.sha256:
                if e.batch_id in open_ids:
                    continue  # in flight: collected by collect_batch()
                todo.append(d)  # stale submission (batch record lost): convert again
            elif self.manifest.needs_conversion(d.key, d.sha256, force=force):
                todo.append(d)
        return todo, skipped

    def failed_docs(self) -> list[SourceDocument]:
        docs, _ = self.scan()
        by_key = {d.key: d for d in docs}
        return [
            by_key[k]
            for k, e in sorted(self.manifest.entries.items())
            if e.status == mf.FAILED and k in by_key
        ]

    def choose_batch(self, n: int, batch: bool | None) -> bool:
        return batch if batch is not None else n > self.cfg.converter.batch_threshold

    # ------------------------------------------------------------------ sync

    def run_sync(self, docs: list[SourceDocument]) -> RunReport:
        report = RunReport()
        workers = max(1, min(self.cfg.converter.concurrency, len(docs)))
        with ThreadPoolExecutor(max_workers=workers) as pool:
            for doc, ok in zip(docs, pool.map(self._convert_one, docs)):
                self._record(report, doc, ok)
        return report

    # ------------------------------------------------------------------ batch

    def submit_batch(self, docs: list[SourceDocument]) -> str:
        requests, custom_ids = [], {}
        for i, doc in enumerate(docs):
            cid = f"r{i:04d}-{doc.sha256[:12]}"
            custom_ids[cid] = doc.key
            requests.append({"custom_id": cid, "params": self._params(doc)})
        batch_id = self.client.batch_create(requests)
        with self._lock:
            for cid, key in custom_ids.items():
                doc = next(d for d in docs if d.key == key)
                e = self._entry(doc)
                e.status, e.batch_id, e.custom_id, e.error = mf.SUBMITTED, batch_id, cid, None
            self.manifest.batches.append(
                Batch(id=batch_id, submitted_at=now_iso(), custom_ids=custom_ids, model=self.cfg.converter.model)
            )
            self.manifest.save()
        self.log(f"Submitted batch {batch_id} with {len(docs)} recipe(s). Its id is saved, so you can")
        self.log("stop this at any time and run `convert run` again to pick up where it left off.")
        return batch_id

    def collect_batch(self, *, wait: bool = True) -> RunReport:
        """Poll the open batch until it ends, then process its results."""
        report = RunReport()
        batch = self.manifest.open_batch()
        if batch is None:
            return report
        last = None
        while True:
            state = self.client.batch_state(batch.id)
            if state.processing_status == "ended":
                break
            if not wait:
                report.still_running = batch.id
                return report
            msg = f"Batch {batch.id}: {state.processing_status} {state.counts}"
            if msg != last:
                self.log(msg + f" — checking every {self.cfg.converter.poll_seconds}s (Ctrl+C to stop; it keeps running)")
                last = msg
            self.sleep(self.cfg.converter.poll_seconds)

        docs, _ = self.scan()
        by_key = {d.key: d for d in docs}
        seen: set[str] = set()
        for cid, response in self.client.batch_results(batch.id):
            key = batch.custom_ids.get(cid)
            if key is None:
                continue
            seen.add(key)
            entry = self.manifest.get(key)
            doc = by_key.get(key)
            if doc is None:
                self.log(f"  {key}: source file is gone; result discarded")
                with self._lock:
                    self.manifest.entries.pop(key, None)
                continue
            if entry and entry.sha256 != doc.sha256:
                self.log(f"  {key}: source changed since submission; it will be converted again")
                with self._lock:
                    entry.status = mf.PENDING
                continue
            self._record(report, doc, self._convert_one(doc, response))

        with self._lock:
            for key in set(batch.custom_ids.values()) - seen:
                e = self.manifest.get(key)
                if e and e.status == mf.SUBMITTED:
                    e.status, e.error = mf.FAILED, "missing from batch results"
                    report.failed.append(key)
            self.manifest.close_batch(batch.id)
            self.manifest.save()
        return report

    # ------------------------------------------------------------------ one recipe

    def _params(self, doc: SourceDocument, history: list[dict[str, Any]] | None = None) -> dict[str, Any]:
        c = self.cfg.converter
        return request_params(doc, model=c.model, max_tokens=c.max_tokens, effort=c.effort, history=history)

    def _entry(self, doc: SourceDocument) -> Entry:
        e = self.manifest.get(doc.key)
        if e is None:
            e = self.manifest.entries[doc.key] = Entry(sha256=doc.sha256)
        e.sha256 = doc.sha256
        return e

    def _taken_ids(self, key: str) -> set[str]:
        on_disk = {
            p.name[: -len(".json")]
            for p in self.out_dir.glob("*.json")
            if not p.name.endswith(".user.json")
        }
        own = self.manifest.get(key)
        own_id = own.recipe_id if own else None
        return (self.manifest.recipe_ids_in_use(except_key=key) | on_disk) - {own_id}

    def _convert_one(self, doc: SourceDocument, first: LLMResponse | None = None) -> bool:
        """Validate the response (calling the API if needed); on failure retry once with the errors."""
        history: list[dict[str, Any]] = [{"role": "user", "content": user_content(doc)}]
        response = first
        for attempt in (1, 2):
            if response is None:
                response = self.client.create(self._params(doc, history))
            with self._lock:
                self._entry(doc).attempts += 1
            if response.error:
                return self._fail(doc, [response.error], response)

            data, errors = parse_output(response.text, response.stop_reason)
            outcome = None
            if data is not None:
                with self._lock:
                    prev = self.manifest.get(doc.key)
                    rid = prev.recipe_id if prev and prev.recipe_id else None
                    if rid is None:
                        rid = unique_id(str(data.get("id") or data.get("title") or doc.path.stem), self._taken_ids(doc.key))
                        self._entry(doc).recipe_id = rid  # reserve it
                created = self._existing_created_at(rid)
                outcome = finalize(data, doc, recipe_id=rid, created_at=created)
                errors = outcome.errors

            if not errors and outcome is not None:
                self._write(doc, outcome.recipe)
                return True

            self._save_failed_output(doc, attempt, response, errors)
            retryable = data is not None or (response.text and response.stop_reason not in ("refusal", "max_tokens"))
            if attempt == 1 and retryable:
                self.log(f"  {doc.key}: {len(errors)} validation problem(s); asking the model to fix them")
                assistant = [b for b in response.content if b.get("type") in HISTORY_BLOCK_TYPES]
                if not assistant:
                    assistant = [{"type": "text", "text": response.text or ""}]
                history += [
                    {"role": "assistant", "content": assistant},
                    {"role": "user", "content": retry_content(errors)},
                ]
                response = None
                continue
            return self._fail(doc, errors, response)
        return False  # pragma: no cover

    def _existing_created_at(self, rid: str) -> str | None:
        path = self.out_dir / f"{rid}.json"
        if path.exists():
            try:
                return json.loads(path.read_text(encoding="utf-8")).get("created_at")
            except (OSError, json.JSONDecodeError):
                return None
        return None

    def _write(self, doc: SourceDocument, recipe: dict[str, Any]) -> None:
        rid = recipe["id"]
        write_text_atomic(self.out_dir / f"{rid}.source.txt", doc.text)
        write_json_atomic(self.out_dir / f"{rid}.json", recipe)
        with self._lock:
            e = self._entry(doc)
            e.recipe_id = rid
            e.status = mf.NEEDS_REVIEW if recipe["status"] == "needs_review" else mf.DONE
            e.error = None
            e.batch_id = e.custom_id = None
            e.model = self.cfg.converter.model
            e.prompt_version = prompt_version()
            e.converted_at = now_iso()
            self.manifest.save()
        flags = len(recipe.get("review_flags", []))
        note = f" — {flags} thing(s) to review" if flags else ""
        self.log(f"  ✓ {doc.key} → {rid}.json{note}")

    def _save_failed_output(self, doc: SourceDocument, attempt: int, response: LLMResponse, errors: list[str]) -> None:
        stem = doc.key.replace("/", "__")
        folder = self.state_dir / "failed"
        write_text_atomic(folder / f"{stem}.attempt{attempt}.output.json", response.text or "")
        write_text_atomic(folder / f"{stem}.attempt{attempt}.errors.txt", "\n".join(errors) + "\n")

    def _fail(self, doc: SourceDocument, errors: list[str], response: LLMResponse) -> bool:
        with self._lock:
            e = self._entry(doc)
            e.status = mf.FAILED
            e.error = "; ".join(errors)[:2000]
            e.batch_id = e.custom_id = None
            self.manifest.save()
        self.log(f"  ✗ {doc.key}: {e.error}")
        return False

    def _record(self, report: RunReport, doc: SourceDocument, ok: bool) -> None:
        e = self.manifest.get(doc.key)
        if ok and e and e.recipe_id:
            (report.needs_review if e.status == mf.NEEDS_REVIEW else report.converted).append(e.recipe_id)
        elif not ok:
            report.failed.append(doc.key)


def write_dry_run_requests(path: Path, conv: Converter, docs: list[SourceDocument]) -> None:
    """For --dry-run: dump the exact requests that would be sent (no API key needed)."""
    write_json_atomic(path, [conv._params(d) for d in docs])
