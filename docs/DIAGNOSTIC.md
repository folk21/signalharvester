---
type: Diagnostic Guide
title: Diagnostics and evidence
description: Entry point for investigating agent runs, backend incidents, and failed verification.
---
# Diagnostics and evidence

Use this page to locate evidence, not as a substitute for the component's owning guide. Record the **run ID, stage, Git commit, observed failure, and last successful check** before changing anything. Preserve evidence first; distinguish an application defect from a test failure, provider failure, or unavailable infrastructure.

## Development Agent Pipeline: Aider / worker / reviewer

A source-bound run stores its evidence in `<runs-root>/<run-id>/`. The default `--runs-root` is `build/reports/agent-pipeline`; opt-in runs can use a private root such as `~/.signalharvester/agent-pipeline-runs`. Use the **same `--runs-root`** supplied at initialization. Do not assume a run lives in the default directory.

| Evidence | What it establishes |
|---|---|
| `manifest.json`, `stage-packet.json`, `task.md` | Base, worker/model selection, authorized scope and task binding (packet where present) |
| `worker-attempt-N.json`, `worker.json` | Attempt order, preflight, model, exit code, duration, changed files and source digest |
| `logs/worker-attempt-N.log` | Combined Aider stdout/stderr for that attempt |
| `aider.input.history`, `aider.chat.history.md` | Run-local Aider input/conversation history; **private raw content** |
| `repair-task-N.md` | Exact additional task used for a repair attempt |
| `checks.json`, `logs/` | Gitleaks/canonical check outcomes, log paths and SHA-256 digests |
| `review-attempt.json`, `review.json` | Provider/schema failure diagnostics or a completed independent review |
| `cycle.json`, `sequence-result.json`, `status.json` | Automated-cycle/checkpoint outcome, when present; not developer acceptance |

Files are created only after their corresponding phase; absent reviewer or sequence artifacts do not imply success.

Quick **read-only** inspection of worker attempts (replace the run ID and root):

```bash
RUNS_ROOT="$HOME/.signalharvester/agent-pipeline-runs"
RUN_ID="<run-id>"
python3 - "$RUNS_ROOT/$RUN_ID" <<'PY'
import json
import sys
from pathlib import Path

run = Path(sys.argv[1])
for path in sorted(run.glob("worker-attempt-*.json")):
    data = json.loads(path.read_text(encoding="utf-8"))
    print({key: data.get(key) for key in
           ("attempt", "tool", "model", "status", "exitCode", "durationMs", "failureReason", "log")})
    preflight = data.get("preflight") or {}
    print("  preflight:", preflight.get("status"),
          "aiderVersion:", preflight.get("version"))
PY
```

**Investigation order:**

1. Compare the model in `worker-attempt-N.json` with the run's `manifest.json` and the reviewed `tools/agent-pipeline/pipeline-policy.json`; check preflight/version and allowed model before blaming generated code. Never print credentials or an environment dump.
2. For `FAIL` or no source changes, inspect that attempt's log and `failureReason`. Context-limit messages, provider timeouts and malformed model replies are **execution diagnostics**, not proof of a source-code defect. A zero process exit alone is not worker success.
3. For failed checks, use `checks.json` to select the failed check/log. For reviewer transport/schema failures, inspect `review-attempt.json` `reasonCode`/`httpStatus` without exposing provider responses. Verify behavior against spec and independent tests; never weaken a valid test solely for PASS.
4. For mismatched digests, missing artifacts or interrupted cycles, preserve the run and stop. Do not rewrite evidence, recycle a run ID, replay a consumed cycle or treat `READY_FOR_MAIN_REVIEW` as acceptance. Escalate with a sanitized summary.

**Retention and confidentiality:** `./run_checks.sh` runs Gradle `clean`; runs inside `build/` may be deleted. Before such a command, use the private `export-subspec` / `verify-subspec-export` workflow, or initialize with a persistent runs root. Keep raw logs, tasks, model histories and exported evidence **outside Git and public tickets**. They may contain code, confidential paths or secrets; redact before sharing. Gitleaks evidence does not retain raw secret matches.

**Current observability gap:** model IDs, Aider version/preflight and attempt/check results are recorded, but structured per-request provider IDs, actual input/output/cache token counts and costs are **not guaranteed**. Missing usage data means *unknown*, not zero. For full commands, repair limits, custody, and reviewer probes, see [`tools/agent-pipeline/README.md`](../tools/agent-pipeline/README.md).

## Other diagnostic entry points

| Area | Start with |
|---|---|
| Failed builds/tests | [`docs/TESTS.md`](TESTS.md) for focused checks and Gradle reports under `build/reports/tests/` |
| Collection/analysis/results | [`docs/USAGE.md`](USAGE.md) for run-specific APIs; correlate IDs with Event Observation and Processing Flow |
| Runtime observability | [`infra/kubernetes/README.md`](../infra/kubernetes/README.md) for Grafana, Prometheus, Loki and Tempo |
| Local Kubernetes failures | [`infra/kubernetes/README.md`](../infra/kubernetes/README.md) for node/workload readiness, events and non-destructive troubleshooting |
| Health/incident investigation | [`modules/operations/contract.md`](../modules/operations/contract.md) for Health semantics; [`docs/IMPLEMENTATION.md`](IMPLEMENTATION.md) for wiring |

Retain the smallest evidence set that reproduces a problem (IDs, timestamps, error classification and failing checks). Never share credentials, personal data or full model transcripts without specific review and redaction.
