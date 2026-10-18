"""`mise-server` — serve the recipes folder to the Mise app over the home network."""

from __future__ import annotations

import argparse
import logging
import sys

import uvicorn

from mise_shared import __version__
from mise_shared.config import load_config

from .app import create_app
from .library import RecipeLibrary, start_watcher
from .pairing import Candidate, lan_candidates, pairing_uri, terminal_qr, wsl_nat_warning


def choose_address(advertise: str, candidates: list[Candidate]) -> tuple[str, list[Candidate]]:
    """The host to put in the QR code (an override wins), plus the other candidates."""
    if advertise:
        return advertise, [c for c in candidates if c.ip != advertise]
    if not candidates:
        return "127.0.0.1", []
    return candidates[0].ip, candidates[1:]


def _describe(c: Candidate) -> str:
    name = f" ({c.adapter})" if c.adapter else ""
    hint = "  ← probably a virtual adapter (WSL/Docker/VM/VPN); the phone can't reach it" if c.likely_virtual else ""
    return f"{c.ip}{name}{hint}"


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="mise-server", description=__doc__)
    p.add_argument("--config", help="path to config.toml (default: repo root, or $MISE_CONFIG)")
    p.add_argument("--port", type=int, help="override server.port")
    p.add_argument(
        "--advertise",
        metavar="ADDRESS",
        help="the PC address to put in the pairing QR code, e.g. 192.168.1.20 (overrides server.advertise_host; default: auto-detect)",
    )
    p.add_argument("--no-qr", action="store_true", help="don't print the pairing QR code")
    args = p.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s", datefmt="%H:%M:%S")
    cfg = load_config(args.config)
    port = args.port or cfg.server.port

    library = RecipeLibrary(cfg.recipes_dir)
    library.load_all()
    start_watcher(library)

    candidates = lan_candidates()
    host, others = choose_address(args.advertise or cfg.server.advertise_host, candidates)
    url = f"http://{host}:{port}"
    uri = pairing_uri(url, cfg.server.token)
    primary = next((c for c in candidates if c.ip == host), None)

    print()
    print(f"  Mise server {__version__} — serving {len(library.ids())} recipe(s) from {cfg.recipes_dir}")
    for bad, why in library.invalid.items():
        print(f"  ! skipped {bad}.json: {why}")
    print(f"  Phone URL:    {url}" + (f"  ({primary.adapter})" if primary and primary.adapter else ""))
    if others:
        print("  Other addresses on this PC (use one of these if the phone can't connect):")
        for c in others:
            print(f"      http://{_describe(c).replace(c.ip, f'{c.ip}:{port}', 1)}")
        print("  To put a different one in the QR code: add --advertise <address>, or set")
        print(f"  advertise_host = \"<address>\" under [server] in {cfg.path}")
    if not (args.advertise or cfg.server.advertise_host) and (warning := wsl_nat_warning(candidates)):
        print("\n  !! " + warning + "\n")
    print(f"  Access token: {cfg.server.token}")
    print(f"  Pairing page: http://localhost:{port}/  (QR codes for every address, on this PC only)")
    if not args.no_qr:
        print("\n  Scan with the Mise app (Settings → Pair with PC):\n")
        print(terminal_qr(uri))
    print("  Press Ctrl+C to stop.\n")
    sys.stdout.flush()

    pairing = [(f"{url}" + (f" ({primary.adapter})" if primary and primary.adapter else ""), uri)]
    pairing += [(f"http://{c.ip}:{port}" + (f" ({c.adapter})" if c.adapter else ""), pairing_uri(f"http://{c.ip}:{port}", cfg.server.token))
                for c in others if not c.likely_virtual]
    app = create_app(library, cfg.server.token, pairing=pairing)
    uvicorn.run(app, host=cfg.server.host, port=port, log_level="info", access_log=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
