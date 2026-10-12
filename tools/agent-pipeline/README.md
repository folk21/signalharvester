---
type: Tool Guide
title: Development agent pipeline foundation
description: Repository-local orchestration foundation for bounded coding workers, reproducible verification evidence, independent review, and main-model checkpoints.
---
# Development agent pipeline foundation

## Purpose

`tools/agent-pipeline/` provides the first repository-local foundation for delegating bounded development work to coding workers such as Aider without making the worker an architecture or acceptance authority.

The goal is to remove routine manual coordination while making omissions visible and fail-closed. Repository rules, active specifications, deterministic checks, and independent review remain authoritative regardless of which model executes a task.

This first slice intentionally does **not** invoke Aider or a model process itself, auto-fix findings, accept a stage, move specifications to the archive, or add external scanner dependencies. Those capabilities should be added only after this evidence format and checkpoint flow are proven on real implementation work.

## Current boundary

The foundation owns:

- creation of a bounded worker task packet;
- required repository/spec preflight, including a clean Git worktree when Git is available;
- a machine-readable run manifest under `build/reports/agent-pipeline/<run-id>/`;
- immutable task/policy digests that detect mid-run worker-contract drift;
- execution and evidence capture for policy-defined and focused verification commands;
- SHA-256 digests for captured command logs;
- structured independent-review ingestion;
- fail-closed state derivation;
- conservative changed-path risk hints when Git history is available;
- a mandatory main-model checkpoint before acceptance.

The foundation does not own product semantics. It does not replace `AGENTS.md`, active specifications, owning module contracts, `./run_checks.sh`, or reviewer judgement.

## State model

A run moves through a deliberately small state model:

```text
PREPARED
   -> VERIFYING
   -> REVIEW_REQUIRED
   -> READY_FOR_MAIN_REVIEW

Any required-check failure or unresolved blocking review finding -> BLOCKED
Explicit review escalation or an open escalation-category finding -> NEEDS_MAIN_REVIEW
```

`READY_FOR_MAIN_REVIEW` is not acceptance. The current policy requires a main-model checkpoint before any acceptance or specification archival decision.

## Policy

[`pipeline-policy.json`](pipeline-policy.json) is the machine-readable policy consumed by the tool. It currently defines:

- repository files that must exist before a run starts;
- the canonical required check (`./run_checks.sh`);
- blocking review severities;
- categories that require escalation;
- conservative path-based risk hints;
- the requirement for a main-model checkpoint before acceptance.

Path-based risk is only an escalation hint. It must never be interpreted as proof that a change is safe. When Git/base-commit evidence is unavailable, risk is reported as `UNKNOWN`.

## Start a worker run

Prepare a task description in a temporary file. The task should describe one bounded implementation slice and its acceptance semantics.

```bash
python3 tools/agent-pipeline/agent_pipeline.py init \
  --stage "6E6" \
  --spec docs/specs/active/subspecs/backend-observability-intelligence.md \
  --task-file /tmp/stage-6e6-task.md \
  --worker aider \
  --model deepseek \
  --scope modules/operations \
  --scope infra/kubernetes
```

Initialization fails when a Git worktree is dirty. Commit or otherwise resolve the current base first so the run has an unambiguous comparison point.

The command prints the run directory. It contains:

```text
build/reports/agent-pipeline/<run-id>/
    manifest.json
    task.md
    checks.json
    reviews.json
```

`task.md` is the worker packet. It makes required repository reading, escalation categories, the acceptance boundary, and required verification explicit.

For contributor-tooling work that is not owned by a product active specification, use `--spec none`. Product implementation work should name the owning active specification explicitly.

## Record focused verification

Additional deterministic checks can be executed and captured without a shell:

```bash
python3 tools/agent-pipeline/agent_pipeline.py run-command \
  --run-id <run-id> \
  --name operations-focused-tests \
  -- ./gradlew :modules:operations:test --no-watch-fs
```

The command output is streamed to the terminal and stored under `logs/`. Every retry receives a distinct attempt log, while `checks.json` retains all attempts with the command, timestamps, duration, exit code, status, log path, and log SHA-256. The latest attempt for each check name controls current state.

A failed focused command blocks the run until the same named check is rerun successfully. Policy-defined required checks must also be present and passing before review can begin.

## Run the canonical gate

The policy-defined canonical check is executed by name so a worker cannot relabel another command as the repository gate:

```bash
python3 tools/agent-pipeline/agent_pipeline.py run-check \
  --run-id <run-id> \
  --name canonical
```

The current `canonical` command is exactly `./run_checks.sh`.

## Record independent review

Independent review should run in a fresh model/context rather than reuse the implementation conversation. The reviewer writes a JSON object such as:

```json
{
  "schemaVersion": 1,
  "reviewer": "deepseek-independent-review",
  "verdict": "PASS",
  "findings": []
}
```

A finding uses:

```json
{
  "id": "REV-001",
  "severity": "HIGH",
  "category": "CONCURRENCY",
  "summary": "Lease ownership is not revalidated before the write.",
  "status": "OPEN",
  "file": "modules/example/Example.java",
  "line": 42
}
```

Supported verdicts are `PASS`, `CHANGES_REQUIRED`, and `ESCALATE`. Supported severities are `INFO`, `LOW`, `MEDIUM`, `HIGH`, and `CRITICAL`.

Record the review with:

```bash
python3 tools/agent-pipeline/agent_pipeline.py record-review \
  --run-id <run-id> \
  --input /tmp/review.json
```

Open `HIGH` or `CRITICAL` findings block the run. Open findings in configured escalation categories such as `SECURITY`, `CONTRACT`, `PERSISTENCE`, `CONCURRENCY`, or `SPEC_AMBIGUITY` require a main-model checkpoint even when their severity is lower.

## Inspect status

```bash
python3 tools/agent-pipeline/agent_pipeline.py status --run-id <run-id>
```

The command updates `manifest.json`, writes `status.json`, and prints the current state plus conservative risk evidence.

The first foundation always ends successful worker automation at `READY_FOR_MAIN_REVIEW`. It deliberately has no `accept`, `archive-spec`, or merge command.

## Evidence retention

Run evidence is written under `build/reports/` and is intentionally excluded from FULL source archives. Preserve or share a run directory separately when a main-model review needs the raw logs and structured evidence.

Do not copy generated run evidence into owning documentation. Accepted stable behavior belongs in the repository documents that already own that topic.

## Planned follow-up after the pilot

Only after this foundation is exercised on a real stage should later slices consider:

- direct Aider process invocation and isolated worktree/branch setup;
- bounded automatic repair/re-review loops;
- secret scanning such as Gitleaks through an explicit repository-owned quality policy;
- structured SpotBugs/dependency-analysis ingestion instead of only command exit evidence;
- automatic handoff-bundle generation for main-model review;
- risk-based checkpoint batching for low-risk slices;
- acceptance tokens and mechanical specification archival after external acceptance.

These are intentionally not implemented by this patch.
