#!/usr/bin/env bash
# Convert raw recipes in inbox/ into structured JSON in recipes/.  Examples:
#   scripts/convert.sh run          scripts/convert.sh status          scripts/convert.sh retry
set -euo pipefail
cd "$(dirname "$0")/.."
[ -x .venv/bin/convert ] || { echo "Run scripts/setup.sh first." >&2; exit 1; }
exec .venv/bin/convert "$@"
