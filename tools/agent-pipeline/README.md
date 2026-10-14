---
type: Tool Guide
title: Development agent pipeline
description: Repository-local orchestration for isolated coding-worker execution, reproducible verification evidence, independent review, and main-model checkpoints.
---
# Development agent pipeline

## Purpose

`tools/agent-pipeline/` provides repository-local orchestration for bounded coding workers without making a worker an architecture or acceptance authority.

The goal is to remove routine manual coordination while making omissions visible and fail-closed. Repository rules, active specifications, deterministic checks, and independent review remain authoritative regardless of which model executes a task.

Stage A1 is accepted after canonical developer verification. Stage A2.1 adds verification-pending isolated Git worktree preparation and one-shot Aider execution. The current A2.1 hardening also fail-closes on unsupported Aider versions/options and isolates the worker from ambient Aider YAML/environment/dotenv configuration before model access. Stage A2.2 adds repository-owned Gitleaks scanning and ordered required-check orchestration. Stage A2.3 introduces a verification-pending fresh-context reviewer adapter and compact external-checkpoint handoff. Repair loops, automatic acceptance, specification archival, and merging remain later stages owned by the active [`development-agent-pipeline`](../../docs/specs/active/subspecs/development-agent-pipeline.md) specification.

## Current boundary

The pipeline owns:

- creation of a bounded worker task packet;
- required repository/spec preflight and a clean Git base;
- an immutable base commit for every Git-backed run;
- isolated worker branch/worktree preparation;
- one-shot Aider execution with a repository-owned argument policy;
- pre-model Aider CLI/version compatibility, Git-identity, and runtime-configuration preflight evidence;
- worker stdout/stderr, exit status, changed-file list, source-change digest, HEAD identity, and log digest evidence;
- prevention of worker auto-commits in the A2.1 adapter;
- directory-scoped Gitleaks scanning of the complete worker worktree, including uncommitted and untracked changes, before canonical verification;
- redacted scanner evidence without persisting raw secret matches or scanner output;
- sequential policy-required verification through one `verify` command;
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
- the pinned Gitleaks executable/version and repository-owned `gitleaks.toml` configuration;
- ordered required checks (Gitleaks first, then `./run_checks.sh`);
- blocking review severities;
- categories that require escalation;
- conservative path-based risk hints;
- the requirement for a main-model checkpoint before acceptance.

The A2.1 adapter currently uses Aider `0.86.2` with `deepseek/deepseek-chat` by default. Provider credentials stay outside repository policy and command evidence; export provider-native variables such as `DEEPSEEK_API_KEY` in the launching environment. Pipeline execution strips `AIDER_*` variables, disables python-dotenv loading, and supplies an empty run-local `--config`, so home/repository `.aider.conf.yml` and `.env` files cannot silently change the worker contract. Ambient home/repository `.aider.model.settings.yml` or `.aider.model.metadata.json` files are rejected because Aider merges those independently of `--config`. Unsupported Aider versions or configured CLI switches fail before model access. Git `user.name` and `user.email` must already resolve so Aider cannot fill missing identity by mutating the shared repository Git configuration. The adapter disables Aider auto-commits, automatic lint/test execution, model-suggested shell commands, release-note/update prompts, URL prompts, analytics, and `.gitignore` mutation. Aider input/chat history is redirected into the run evidence directory instead of the worker tree. Pipeline verification remains responsible for command execution and evidence.

Stage A2.2 requires Gitleaks `8.30.1` to be installed on `PATH` before executing pipeline verification; installation is external and never performed by the worker. The pinned `tools/agent-pipeline/gitleaks.toml` extends Gitleaks default detection rules and excludes generated build/IDE/Git internals. The directory scan targets the whole worker filesystem rather than Git history alone, so it covers Aider's uncommitted edits and new files. It rejects worker-created `.gitleaksignore`, disables inline `gitleaks:allow` suppressions, and refuses changes to the scanner config, `pipeline-policy.json`, or `run_checks.sh` inside a worker run. Scanner version/config/source identity, exit status, finding count, rule/file/line metadata, and redacted failure reason are recorded in `checks.json`. Raw secret values, report JSON, and scanner stdout/stderr are not retained; the temporary report is deleted. Some Gitleaks releases do not create an empty report when no findings exist; zero exit status remains authoritative for a successful clean scan. Missing binary, incompatible version, malformed finding report, detected secrets, or an unsuccessful scan blocks progression. This scanner does not replace independent review or separate historical Git secret scanning.

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
    checks.json                 # Gitleaks summary and check attempts
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
- Aider preflight version/CLI compatibility, sanitized-environment names, Git-identity readiness, and run-local config digest;
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

## Run required verification

After the one-shot worker passes, run all policy-defined required checks in order:

```bash
python3 tools/agent-pipeline/agent_pipeline.py verify \
  --run-id <run-id>
```

This runs `gitleaks` first, then the canonical `./run_checks.sh` inside the isolated worker worktree. A failed step stops the sequence, records a failed attempt, and blocks progression. The command never installs tools or invokes the model. Optional task-specific focused checks continue to use `run-command` and should be run before `verify` when the task requires them.

For a single required check or an explicit retry:

```bash
python3 tools/agent-pipeline/agent_pipeline.py run-check \
  --run-id <run-id> \
  --name gitleaks

python3 tools/agent-pipeline/agent_pipeline.py run-check \
  --run-id <run-id> \
  --name canonical
```

The latest attempt for each required check determines state. A `PASS` claim without matching Gitleaks scanner/source evidence is rejected. The pipeline refuses verification before required worker execution has passed.

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

Run evidence and worker worktrees are under ignored `build/` paths; do not include them in normal repository context snapshots. Preserve or share a run directory separately when a main-model review needs raw logs and structured evidence.

Do not copy generated run evidence into owning documentation. Accepted stable behavior belongs in the repository documents that already own that topic.

## Specification and next stages

The active [`development-agent-pipeline`](../../docs/specs/active/subspecs/development-agent-pipeline.md) specification owns intended evolution, stage sequencing, and final acceptance criteria. This guide owns current implemented commands and evidence semantics only.

A2.1, A2.2, and A2.3 remain developer-verification-pending. A first real Aider documentation pilot reached DeepSeek but applied no changes after edit-format mismatches; that attempt was blocked by the external pilot scope guard before Gitleaks and independent review. The next implementation slice is A3 bounded model repair and re-review, including intrinsic no-diff blocking. The future sub-spec delegation map, stage runner, guarded feature-branch commits, notifications, and lifecycle gates are specified in the active specification, **not implemented by these commands**.


## Automatic independent review and main checkpoint handoff (A2.3)

After `verify` passes, invoke the read-only reviewer from a fresh provider request:

```bash
python3 tools/agent-pipeline/agent_pipeline.py run-review --run-id <run-id>
python3 tools/agent-pipeline/agent_pipeline.py handoff --run-id <run-id>
```

The reviewer uses the policy-owned HTTPS DeepSeek chat-completions endpoint and model, with `DEEPSEEK_API_KEY` supplied by the environment. It receives only the immutable task packet, bounded worker source diff (including untracked files), changed-file names, and check statuses. Worker chat transcripts are not forwarded. A new independent request is created for each run, and its answer must match the repository's structured review schema. The worker cannot run or accept its own review. Review attempts are one-shot; provider/schema failures are recorded as `review-attempt.json` without saving raw API responses and block the run. A larger diff is rejected rather than silently truncated. Never send sensitive code to an unapproved provider.

`handoff.json` summarizes task/spec/base identity, worker change digest/files, required checks, independent review findings, status/risk and evidence paths. It is an index for the main-model checkpoint, not acceptance or proof that every review issue is resolved. `record-review` remains available for externally supplied independent evidence, but it must not be used as a worker self-review. Neither command merges branches, repairs code, or archives specifications.
