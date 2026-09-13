#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT_DIR"

PYTHON_BIN=${PYTHON_BIN:-python3}
command -v "$PYTHON_BIN" >/dev/null 2>&1 || {
  echo "ERROR: $PYTHON_BIN is required for source-import tests" >&2
  exit 1
}

PYTHONDONTWRITEBYTECODE=1 "$PYTHON_BIN" -m unittest discover \
  -s tools/source-import/tests \
  -p 'test_*.py' \
  -v
