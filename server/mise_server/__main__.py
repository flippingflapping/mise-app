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
from .pairing import lan_ips, pairing_uri, terminal_qr


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="mise-server", description=__doc__)
    p.add_argument("--config", help="path to config.toml (default: repo root, or $MISE_CONFIG)")
    p.add_argument("--port", type=int, help="override server.port")
    p.add_argument("--no-qr", action="store_true", help="don't print the pairing QR code")
    args = p.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s", datefmt="%H:%M:%S")
    cfg = load_config(args.config)
    port = args.port or cfg.server.port

    library = RecipeLibrary(cfg.recipes_dir)
    library.load_all()
    start_watcher(library)

    ips = lan_ips()
    url = f"http://{ips[0]}:{port}"
    uri = pairing_uri(url, cfg.server.token)
    print()
    print(f"  Mise server {__version__} — serving {len(library.ids())} recipe(s) from {cfg.recipes_dir}")
    for bad, why in library.invalid.items():
        print(f"  ! skipped {bad}.json: {why}")
    print(f"  Phone URL:    {url}")
    for other in ips[1:]:
        print(f"  (also maybe:  http://{other}:{port})")
    print(f"  Access token: {cfg.server.token}")
    print(f"  Pairing page: http://localhost:{port}/  (QR code in your browser, this PC only)")
    if not args.no_qr:
        print("\n  Scan with the Mise app (Settings → Pair with PC):\n")
        print(terminal_qr(uri))
    print("  Press Ctrl+C to stop.\n")
    sys.stdout.flush()

    app = create_app(library, cfg.server.token, pairing_uri=uri)
    uvicorn.run(app, host=cfg.server.host, port=port, log_level="info", access_log=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
