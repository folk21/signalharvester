#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
PIPE="tools/agent-pipeline/agent_pipeline.py"
PACKET="tools/agent-pipeline/examples/a4-pilot-stage-packet.json"
RUN_ID="a4-pilot-$(date -u +%Y%m%dT%H%M%SZ)-$$"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "ERROR: Commit or stash existing changes before the isolated live pilot." >&2
  exit 1
fi
for executable in python3 aider gitleaks; do
  command -v "$executable" >/dev/null 2>&1 || { echo "ERROR: Missing $executable" >&2; exit 1; }
done
if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
  printf 'DeepSeek API key (hidden): ' >&2
  IFS= read -r -s DEEPSEEK_API_KEY
  echo >&2
  export DEEPSEEK_API_KEY
fi
if [[ -z "$DEEPSEEK_API_KEY" ]]; then
  echo 'ERROR: DEEPSEEK_API_KEY is required.' >&2
  exit 1
fi

echo "RUN_ID=$RUN_ID"
python3 "$PIPE" init-stage --run-id "$RUN_ID" --packet "$PACKET"
python3 "$PIPE" prepare-worker --run-id "$RUN_ID"
RESULT=0
python3 "$PIPE" run-cycle --run-id "$RUN_ID" || RESULT=$?
WT="build/agent-worktrees/$RUN_ID"
EVIDENCE="build/reports/agent-pipeline/$RUN_ID"
git -C "$WT" status --short
git -C "$WT" diff --check HEAD || RESULT=1
git -C "$WT" diff HEAD -- \
  tools/agent-pipeline/agent_pipeline.py \
  tools/agent-pipeline/tests/test_agent_pipeline.py > "$EVIDENCE/change.patch"
python3 "$PIPE" status --run-id "$RUN_ID" || RESULT=1
printf 'Evidence: %s\n' "$EVIDENCE"
printf 'Review files: cycle.json, manifest.json, worker.json, checks.json, review.json, handoff.json, change.patch\n'
if [[ "$RESULT" -ne 0 ]]; then
  echo 'Pilot requires developer escalation. Preserve this RUN_ID; do not rerun it.' >&2
fi
exit "$RESULT"
