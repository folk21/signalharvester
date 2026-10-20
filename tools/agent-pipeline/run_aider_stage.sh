#!/bin/sh
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT_DIR"
PYTHON_BIN=${PYTHON_BIN:-python3}
"$PYTHON_BIN" - <<'PY'
import json
from pathlib import Path
policy = json.loads(Path("tools/agent-pipeline/pipeline-policy.json").read_text(encoding="utf-8"))
if policy.get("workerExecution", {}).get("tool") != "aider":
    raise SystemExit("ERROR: run_aider_stage.sh accepts only workerExecution.tool=aider")
PY
exec "$PYTHON_BIN" tools/agent-pipeline/agent_pipeline.py "$@"
