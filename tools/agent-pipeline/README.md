---
type: Tool Guide
title: Development agent pipeline
description: Repository-local orchestration for isolated coding-worker execution, reproducible verification evidence, independent review, and main-model checkpoints.
---
# Development agent pipeline

## Purpose

`tools/agent-pipeline/` provides repository-local orchestration for bounded coding workers without making a worker an architecture or acceptance authority.

The goal is to remove routine manual coordination while making omissions visible and fail-closed. Repository rules, active specifications, deterministic checks, and independent review remain authoritative regardless of which model executes a task.

Stage A1 is accepted after canonical developer verification. Stage A2.1 adds verification-pending isolated Git worktree preparation and one-shot Aider execution. It deliberately does **not** add repair loops, automatic independent-review invocation, automatic acceptance, specification archival, merging, or secret-scanner integration. Those remain later stages owned by the active [`development-agent-pipeline`](../../docs/specs/active/subspecs/development-agent-pipeline.md) specification.

## Current boundary

The pipeline owns:

- creation of a bounded worker task packet;
- required repository/spec preflight and a clean Git base;
- an immutable base commit for every Git-backed run;
- isolated worker branch/worktree preparation;
- one-shot Aider execution with a repository-owned argument policy;
- worker stdout/stderr, exit status, changed-file list, source-change digest, HEAD identity, and log digest evidence;
- prevention of worker auto-commits in the A2.1 adapter;
- execution of focused and canonical verification inside the isolated worker worktree;
- immutable task/policy digests that detect mid-run worker-contract drift;
- structured independent-review ingestion;
- fail-closed state derivation;
- conservative changed-path risk hints;
- a mandatory main-model checkpoint before acceptance.

The pipeline does not own product semantics. It does not replace `AGENTS.md`, active specifications, owning module contracts, `./run_checks.sh`, or reviewer judgement.

## State model

A direct-worker run uses this deliberately small progression:

```text
PREPARED
   -> WORKER_REQUIRED
   -> VERIFYING
   -> REVIEW_REQUIRED
   -> READY_FOR_MAIN_REVIEW

Worker/check failure or unresolved blocking review finding -> BLOCKED
Explicit review escalation or an open escalation-category finding -> NEEDS_MAIN_REVIEW
```

`READY_FOR_MAIN_REVIEW` is not acceptance. The current policy requires a main-model checkpoint before any acceptance or specification archival decision.

## Policy

[`pipeline-policy.json`](pipeline-policy.json) is the machine-readable policy consumed by the tool. It defines:

- repository files that must exist before a run starts;
- the required one-shot worker adapter;
- the default worker model;
- safe Aider command-line arguments;
- the canonical required check (`./run_checks.sh`);
- blocking review severities;
- categories that require escalation;
- conservative path-based risk hints;
- the requirement for a main-model checkpoint before acceptance.

The A2.1 adapter currently uses Aider with `deepseek/deepseek-chat` by default. Provider credentials stay outside repository policy and command evidence; configure them through the environment expected by Aider. The adapter disables Aider auto-commits, automatic lint/test execution, model-suggested shell commands, update checks, URL prompts, analytics, and `.gitignore` mutation. Aider input/chat history is redirected into the run evidence directory instead of the worker tree. Pipeline verification remains responsible for command execution and evidence.

Path-based risk is only an escalation hint. It must never be interpreted as proof that a change is safe. When Git/base-commit evidence is unavailable, risk is reported as `UNKNOWN`.

## Initialize a run

Prepare a bounded task description in a temporary file:

```bash
python3 tools/agent-pipeline/agent_pipeline.py init \
  --stage "A2.1-example" \
  --spec docs/specs/active/subspecs/development-agent-pipeline.md \
  --task-file /tmp/agent-task.md \
  --scope tools/agent-pipeline
```

The configured default is Aider with `deepseek/deepseek-chat`. Override the model only when the task intentionally requires another provider/model:

```bash
python3 tools/agent-pipeline/agent_pipeline.py init \
  --stage "example" \
  --spec docs/specs/active/subspecs/example.md \
  --task-file /tmp/task.md \
  --model deepseek/deepseek-chat
```

Initialization fails when the base Git worktree is dirty. The base must remain unchanged until the isolated worker worktree is prepared.

For contributor-tooling work that is not owned by a product active specification, use the owning contributor-tooling specification rather than `--spec none` when one exists. `--spec none` remains available for genuinely specification-free tooling maintenance.

The run directory contains machine-readable evidence under:

```text
build/reports/agent-pipeline/<run-id>/
    manifest.json
    task.md
    worker.json                 # after worker execution
    checks.json
    reviews.json
    review.json                 # after independent review
    status.json
    logs/
```

## Prepare the isolated worker workspace

```bash
python3 tools/agent-pipeline/agent_pipeline.py prepare-worker \
  --run-id <run-id>
```

The command:

1. verifies that the base worktree is still clean and still at the recorded base commit;
2. creates a dedicated `agent/<run-id>` branch;
3. creates `build/agent-worktrees/<run-id>` from the exact base commit;
4. records branch/worktree/base identity in `manifest.json`.

The main developer worktree remains untouched by worker edits.

A prepared worktree is intentionally retained for review and later developer action. A2.1 has no automatic merge or destructive cleanup command.

## Run the one-shot worker

Ensure Aider and the selected model credentials are configured in the developer environment, then run:

```bash
python3 tools/agent-pipeline/agent_pipeline.py run-worker \
  --run-id <run-id>
```

The A2.1 worker is one-shot. A second `run-worker` invocation for the same run is rejected. Bounded repair/retry belongs to A3; until then, a failed worker execution requires review and a new run rather than an implicit self-repair loop.

`worker.json` records:

- tool/model identity;
- exact non-secret command arguments;
- timestamps/duration/exit code;
- workspace branch/base;
- worker HEAD after execution;
- whether an unexpected worker commit occurred;
- changed/untracked paths and a content digest for that source state;
- log path and SHA-256.

An unexpected worker commit fails the worker result. The repository-owned adapter requests `--no-auto-commits`, so the expected A2.1 handoff is an uncommitted diff inside the isolated worktree. After `worker.json` is captured, later checks/status also reject any source drift from that recorded worker state.

## Record focused verification

After a passing worker result, additional deterministic checks run **inside the worker worktree**:

```bash
python3 tools/agent-pipeline/agent_pipeline.py run-command \
  --run-id <run-id> \
  --name operations-focused-tests \
  -- ./gradlew :modules:operations:test --no-watch-fs
```

The command output is streamed to the terminal and stored under `logs/`. Every retry receives a distinct attempt log, while `checks.json` retains all attempts with command, timestamps, duration, exit code, status, log path, and log SHA-256. The latest attempt for each check name controls current state.

A failed focused command blocks the run until the same named check is rerun successfully. This check-retry behavior is deterministic verification, not model repair.

## Run the canonical gate

```bash
python3 tools/agent-pipeline/agent_pipeline.py run-check \
  --run-id <run-id> \
  --name canonical
```

The policy-defined `canonical` command is exactly `./run_checks.sh`, and it executes in the isolated worker worktree. The pipeline refuses verification before the required worker execution has passed.

## Record independent review

Independent review still runs outside A2.1 and should use a fresh model/context rather than reuse the implementation conversation. The reviewer writes a JSON object such as:

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

Open `HIGH` or `CRITICAL` findings block the run. Open configured escalation-category findings require a main-model checkpoint even when their severity is lower.

## Inspect status

```bash
python3 tools/agent-pipeline/agent_pipeline.py status --run-id <run-id>
```

The command updates `manifest.json`, writes `status.json`, and reports worker/check/review state plus conservative risk evidence from the isolated worker diff.

## Evidence retention

Run evidence and worker worktrees are under ignored `build/` paths and are intentionally excluded from FULL source archives. Preserve or share a run directory separately when a main-model review needs raw logs and structured evidence.

Do not copy generated run evidence into owning documentation. Accepted stable behavior belongs in the repository documents that already own that topic.

## Specification and next stages

The active [`development-agent-pipeline`](../../docs/specs/active/subspecs/development-agent-pipeline.md) specification owns intended evolution, stage sequencing, and final acceptance criteria. This guide owns current implemented commands and evidence semantics only.

After A2.1 developer verification, the next bounded automation slice is A2.2: repository-owned Gitleaks execution/evidence and tighter automated verification orchestration. A2.3 then focuses on independent-review invocation/handoff. Bounded model repair/re-review remains A3.
