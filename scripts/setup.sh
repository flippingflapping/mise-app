#!/usr/bin/env bash
# One-time setup of the PC tools (converter + server) on macOS / Linux.
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PYTHON:-python3}"
if ! command -v "$PY" >/dev/null 2>&1; then
  echo "Python 3.10+ is required. Install it from https://www.python.org/downloads/ and re-run." >&2
  exit 1
fi
"$PY" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' || {
  echo "Python 3.10 or newer is required (found $("$PY" --version))." >&2; exit 1; }
[ -d .venv ] || "$PY" -m venv .venv
.venv/bin/python -m pip install --quiet --upgrade pip
.venv/bin/python -m pip install --quiet -e ".[dev]"
mkdir -p recipes inbox
.venv/bin/python -c "from mise_shared.config import load_config; c = load_config(); print(f'Config file: {c.path}')"
echo "Setup complete. Start the server with: scripts/server.sh"
