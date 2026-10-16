#!/usr/bin/env bash
# Start the Mise companion server (shows a pairing QR code).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -x .venv/bin/mise-server ] || { echo "Run scripts/setup.sh first." >&2; exit 1; }
exec .venv/bin/mise-server "$@"
