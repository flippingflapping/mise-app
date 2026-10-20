"""`convert` — turn raw recipe text in the inbox into structured recipe JSON, once.

    convert run      [--inbox PATH] [--out PATH] [--batch | --sync] [--force] [--dry-run] [--yes]
    convert status
    convert retry    [--batch | --sync] [--dry-run] [--yes]
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from mise_shared.config import load_config

from . import manifest as mf
from .backends import CHOICES, Backend, BackendUnavailable, resolve
from .cost import estimate
from .engine import Converter, RunReport, write_dry_run_requests
from .prompt import system_prompt


def _parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="convert", description="Convert raw recipes to Mise JSON with Claude.")
    p.add_argument("--config", help="path to config.toml (default: repo root, or $MISE_CONFIG)")
    p.add_argument(
        "--backend", choices=CHOICES,
        help="who converts: api (ANTHROPIC_API_KEY), claude-code (Claude Code CLI with your Claude subscription), "
             "or auto (default from config.toml: API key if set, else Claude Code)",
    )
    sub = p.add_subparsers(dest="command", required=True)

    def common(sp: argparse.ArgumentParser) -> None:
        sp.add_argument("--inbox", type=Path, help="folder of raw .txt/.md recipes (default from config)")
        sp.add_argument("--out", type=Path, help="recipes folder to write to (default from config)")

    run = sub.add_parser("run", help="convert new or changed recipes in the inbox")
    common(run)
    mode = run.add_mutually_exclusive_group()
    mode.add_argument("--batch", dest="batch", action="store_true", default=None, help="use the Message Batches API (50%% cheaper, slower)")
    mode.add_argument("--sync", dest="batch", action="store_false", help="convert a few at a time right now")
    run.add_argument("--force", action="store_true", help="re-convert even if unchanged")
    run.add_argument("--dry-run", action="store_true", help="show what would happen; send nothing")
    run.add_argument("--yes", "-y", action="store_true", help="don't ask for confirmation")
    run.add_argument("--no-wait", action="store_true", help="submit a batch and exit without waiting")

    st = sub.add_parser("status", help="show pending / submitted / done / failed / needs review")
    common(st)

    rt = sub.add_parser("retry", help="re-run failed conversions only")
    common(rt)
    rmode = rt.add_mutually_exclusive_group()
    rmode.add_argument("--batch", dest="batch", action="store_true", default=None)
    rmode.add_argument("--sync", dest="batch", action="store_false")
    rt.add_argument("--dry-run", action="store_true")
    rt.add_argument("--yes", "-y", action="store_true")
    return p


def _confirm(question: str) -> bool:
    try:
        return input(f"{question} [y/N] ").strip().lower() in ("y", "yes")
    except EOFError:
        return False


def _backend(conv: Converter, kind: str | None = None) -> Backend:
    try:
        return resolve(conv.cfg, kind or conv.backend_choice)
    except BackendUnavailable as e:
        sys.exit(str(e))


def _summary(report: RunReport, conv: Converter) -> None:
    print()
    if report.still_running:
        print(f"Batch {report.still_running} is still running. Run `convert run` later to collect it.")
    total = len(report.converted) + len(report.needs_review)
    if total or report.failed:
        print(f"Converted {total} recipe(s) into {conv.out_dir}")
        if report.needs_review:
            print(f"  {len(report.needs_review)} need a quick review in the app (look for the 'Needs review' badge):")
            for rid in report.needs_review:
                print(f"    - {rid}")
        if report.failed:
            print(f"  {len(report.failed)} failed — see `convert status`, fix the text if needed, then `convert retry`.")
            print(f"  Raw model output is kept in {conv.state_dir / 'failed'}")


def _process(conv: Converter, docs, *, batch: bool | None, dry_run: bool, yes: bool, wait: bool) -> int:
    try:
        backend: Backend | None = resolve(conv.cfg, conv.backend_choice)
    except BackendUnavailable as e:
        if not dry_run:
            sys.exit(str(e))
        backend = None
    use_batch = conv.choose_batch(len(docs), batch)
    if backend and not backend.supports_batch:
        if batch:
            print("Batch mode needs the API; Claude Code converts one recipe at a time instead.")
        use_batch = False
    model = (backend and backend.kind == "claude-code" and conv.cfg.converter.claude_code.model) or conv.cfg.converter.model
    print(f"Converting with: {backend.label if backend else '(nothing set up yet)'}")
    print(f"Model: {model} (effort {conv.cfg.converter.effort})")
    for d in docs:
        print(f"  • {d.key}")
    if backend and backend.kind == "claude-code":
        print(f"{len(docs)} recipe(s), one at a time (a minute or two each). Uses your Claude plan's usage limits; no API charges.")
    else:
        print(estimate(docs, len(system_prompt()), conv.cfg.converter.pricing, batch=use_batch).describe())
    if dry_run:
        path = conv.state_dir / "dry-run-requests.json"
        write_dry_run_requests(path, conv, docs)
        print(f"Dry run: nothing sent. The exact requests are in {path}")
        return 0
    if backend and (problem := backend.login_problem()):
        sys.exit(problem)
    if not yes and not _confirm("Go ahead?"):
        print("Cancelled.")
        return 1
    if use_batch:
        conv.submit_batch(docs)
        report = conv.collect_batch(wait=wait)
    else:
        report = conv.run_sync(docs)
    _summary(report, conv)
    return 1 if report.failed else 0


def cmd_run(conv: Converter, args: argparse.Namespace) -> int:
    rc = 0
    if conv.manifest.open_batch():
        b = conv.manifest.open_batch()
        if args.dry_run:
            print(f"A batch is in flight ({b.id}); a real run would wait for it first.")
        else:
            conv._client = _backend(conv, "api").factory()  # batches live on the API
            print(f"Resuming batch {b.id} (submitted {b.submitted_at}).")
            report = conv.collect_batch(wait=not args.no_wait)
            conv._client = None  # new conversions use the configured backend
            _summary(report, conv)
            if report.still_running:
                return 0
            rc = 1 if report.failed else 0
    docs, skipped = conv.plan(force=args.force)
    for s in skipped:
        print(f"  (skipped {s})")
    if not docs:
        print("Nothing new to convert. (Changed files are picked up automatically; use --force to redo all.)")
        return rc
    return _process(conv, docs, batch=args.batch, dry_run=args.dry_run, yes=args.yes, wait=not args.no_wait) or rc


def cmd_retry(conv: Converter, args: argparse.Namespace) -> int:
    docs = conv.failed_docs()
    if not docs:
        print("No failed conversions to retry.")
        return 0
    return _process(conv, docs, batch=args.batch if args.batch is not None else False,
                    dry_run=args.dry_run, yes=args.yes, wait=True)


def cmd_status(conv: Converter, args: argparse.Namespace) -> int:
    m = conv.manifest
    docs, skipped = conv.scan()
    todo, _ = conv.plan()
    pending_keys = {d.key for d in todo}
    labels = {
        mf.DONE: "done",
        mf.NEEDS_REVIEW: "needs review",
        mf.FAILED: "FAILED",
        mf.SUBMITTED: "submitted",
        mf.PENDING: "pending",
    }
    print(f"Inbox:   {conv.inbox}")
    print(f"Recipes: {conv.out_dir}\n")
    rows = []
    for d in docs:
        e = m.get(d.key)
        if d.key in pending_keys and (e is None or e.status != mf.SUBMITTED):
            status = "pending (changed)" if e and e.sha256 != d.sha256 else "pending"
        else:
            status = labels.get(e.status, e.status) if e else "pending"
        rows.append((d.key, status, (e.recipe_id if e else None) or "", (e.error if e and e.status == mf.FAILED else "") or ""))
    width = max([len(r[0]) for r in rows] + [6])
    for key, status, rid, err in rows:
        line = f"  {key:<{width}}  {status:<17} {rid}"
        print(line.rstrip())
        if err:
            print(f"  {'':<{width}}  ↳ {err[:200]}")
    counts: dict[str, int] = {}
    for _, status, _, _ in rows:
        k = status.split(" (")[0]
        counts[k] = counts.get(k, 0) + 1
    print("\n" + (", ".join(f"{v} {k}" for k, v in counts.items()) or "Inbox is empty."))
    for s in skipped:
        print(f"  (skipped {s})")
    b = m.open_batch()
    if b:
        line = f"\nBatch in flight: {b.id}, submitted {b.submitted_at}, {len(b.custom_ids)} recipe(s)."
        if os.environ.get("ANTHROPIC_API_KEY"):
            try:
                st = conv.client.batch_state(b.id)
                line += f" Now: {st.processing_status} {st.counts}"
            except Exception as e:  # status is informational only
                line += f" (couldn't check: {e})"
        print(line + "\nRun `convert run` to wait for it and collect the results.")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    cfg = load_config(args.config)
    conv = Converter(
        cfg,
        lambda: _backend(conv).factory(),
        inbox=args.inbox,
        out_dir=args.out,
    )
    conv.backend_choice = args.backend
    conv.inbox.mkdir(parents=True, exist_ok=True)
    conv.out_dir.mkdir(parents=True, exist_ok=True)
    try:
        return {"run": cmd_run, "status": cmd_status, "retry": cmd_retry}[args.command](conv, args)
    except KeyboardInterrupt:
        b = conv.manifest.open_batch()
        if b:
            print(f"\nStopped waiting. Batch {b.id} keeps running; run `convert run` again to collect it.")
        else:
            print("\nInterrupted.")
        return 130


if __name__ == "__main__":
    sys.exit(main())
