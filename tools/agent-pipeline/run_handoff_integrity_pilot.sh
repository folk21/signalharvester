#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
PIPE="tools/agent-pipeline/agent_pipeline.py"
PACKET="tools/agent-pipeline/examples/a4-handoff-integrity-stage-packet.json"
RUN_ID="a4-handoff-integrity-$(date -u +%Y%m%dT%H%M%SZ)-$$"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "ERROR: Commit or stash changes before starting a new isolated correction run." >&2
  exit 1
fi
BRANCH="$(git branch --show-current)"
if [[ -z "$BRANCH" || "$BRANCH" == agent/* ]]; then
  echo "ERROR: Start from a named clean developer branch, not a detached HEAD or prior agent worktree." >&2
  exit 1
fi
for executable in python3 aider gitleaks zip; do
  command -v "$executable" >/dev/null 2>&1 || {
    echo "ERROR: Missing $executable" >&2
    exit 1
  }
done
if [[ ! -f "$PACKET" ]]; then
  echo "ERROR: Missing Stage Task Packet: $PACKET" >&2
  exit 1
fi
if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
  printf 'DeepSeek API key (hidden): ' >&2
  IFS= read -r -s DEEPSEEK_API_KEY
  echo >&2
  export DEEPSEEK_API_KEY
fi
if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
  echo 'ERROR: DEEPSEEK_API_KEY is required.' >&2
  exit 1
fi
export DEEPSEEK_API_KEY

printf 'RUN_ID=%s\n' "$RUN_ID"
python3 "$PIPE" init-stage --run-id "$RUN_ID" --packet "$PACKET"
python3 "$PIPE" prepare-worker --run-id "$RUN_ID"
RESULT=0
python3 "$PIPE" run-cycle --run-id "$RUN_ID" || RESULT=$?
WT="build/agent-worktrees/$RUN_ID"
EVIDENCE="build/reports/agent-pipeline/$RUN_ID"
if [[ -d "$WT" ]]; then
  git -C "$WT" status --short
  git -C "$WT" diff --check HEAD || RESULT=1
  git -C "$WT" diff --binary HEAD -- \
    tools/agent-pipeline/agent_pipeline.py \
    tools/agent-pipeline/tests/test_agent_pipeline.py > "$EVIDENCE/change.patch"
else
  echo "ERROR: Worker worktree missing: $WT" >&2
  RESULT=1
fi
python3 "$PIPE" status --run-id "$RUN_ID" || RESULT=1

# Include structured evidence and source diff, not raw logs or model output.
files=()
for name in cycle.json manifest.json worker.json checks.json review.json handoff.json change.patch; do
  if [[ -f "$EVIDENCE/$name" ]]; then
    files+=("$EVIDENCE/$name")
  fi
done
for path in "$EVIDENCE"/worker-attempt-*.json; do
  if [[ -f "$path" ]]; then
    files+=("$path")
  fi
done
if ((${#files[@]})); then
  (cd "$EVIDENCE" && zip -q -j review.zip "${files[@]/#/$ROOT/}") || RESULT=1
fi
printf 'Evidence: %s\n' "$EVIDENCE"
printf 'Review archive: %s/review.zip\n' "$EVIDENCE"
if [[ "$RESULT" -ne 0 ]]; then
  echo 'Run requires developer escalation; preserve its RUN_ID and worktree. Do not rerun a terminal cycle.' >&2
fi
exit "$RESULT"
