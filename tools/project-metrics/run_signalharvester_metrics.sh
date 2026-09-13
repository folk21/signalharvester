#!/bin/sh
set -eu

# SignalHarvester-specific project-size definition.
#
# Production SLOC:
# - handwritten src/main/java Java sources outside testing/**;
# - physical source lines containing code only (blank/comment-only lines excluded).
#
# Test SLOC:
# - source files under src/test/**, src/integrationTest/**, testing/**, and tooling tests;
# - Java test comments/blank lines are excluded using the same SLOC semantics.
#
# Documentation/specification metrics:
# - Markdown: all repository Markdown outside test trees, counted as nonblank content lines;
# - Specifications: ALL Markdown under docs/specs/**, including active/completed/archived specs;
# - SpecFactor = all specification content lines / production SLOC.
#
# Project total excluding tests = production SLOC + config/contracts/scripts physical lines + Markdown content lines.
# Raw byte totals always use complete file bytes, including whitespace/comments.
# Generated/build/cache output, archives, and binaries are excluded.

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT_DIR"

PYTHON_BIN=${PYTHON_BIN:-python3}
command -v "$PYTHON_BIN" >/dev/null 2>&1 || {
  echo "ERROR: $PYTHON_BIN is required for project-size metrics" >&2
  exit 1
}

REPORT_DIR="$ROOT_DIR/build/reports/metrics"

PYTHONDONTWRITEBYTECODE=1 "$PYTHON_BIN" tools/project-metrics/count_project_size.py \
  --root "$ROOT_DIR" \
  --code-glob '**/src/main/java/**/*.java' \
  --project-glob '**/*.java' \
  --project-glob '**/*.kt' \
  --project-glob '**/*.kts' \
  --project-glob '**/*.py' \
  --project-glob '**/*.sh' \
  --project-glob '**/*.bat' \
  --project-glob '**/*.proto' \
  --project-glob '**/*.yaml' \
  --project-glob '**/*.yml' \
  --project-glob '**/*.json' \
  --project-glob '**/*.toml' \
  --project-glob '**/*.properties' \
  --project-glob '**/*.sql' \
  --project-glob '**/*.xml' \
  --project-glob '**/*.conf' \
  --docs-glob '**/*.md' \
  --spec-glob 'docs/specs/**/*.md' \
  --project-glob '**/.env.example' \
  --project-glob '**/Dockerfile' \
  --project-glob 'gradlew' \
  --test-root '**/src/test/**' \
  --test-root '**/src/integrationTest/**' \
  --test-root 'testing/**' \
  --test-root 'tools/**/tests/**' \
  --report "$REPORT_DIR/project-size.txt" \
  --json-report "$REPORT_DIR/project-size.json" \
  --console-summary
