---
type: Tool Guide
title: Development agent pipeline
description: Repository-local orchestration for isolated coding-worker execution, reproducible verification evidence, independent review, and main-model checkpoints.
---
# Development agent pipeline

## Purpose

`tools/agent-pipeline/` provides repository-local orchestration for bounded coding workers without making a worker an architecture or acceptance authority.

The goal is to remove routine manual coordination while making omissions visible and fail-closed. Repository rules, active specifications, deterministic checks, and independent review remain authoritative regardless of which model executes a task.

Stage A1 is accepted after canonical developer verification. A2.1–A2.3,
A3, and A4.1 provide isolated Aider worktrees, pinned preflight, security and
canonical checks, independent review, bounded repair, and source-bound stage
packets. A4.2a adds read-only single-run evidence aggregation; its live pilot
passed worker/check/oracle gates but failed reviewer invocation. The later
`MAIN_MODEL` recovery passed developer checks, without transferring a reviewer
PASS from the old source state. These slices remain verification-pending at
their respective external acceptance boundaries.

A4.2b structured worker deviations now has a recovery implementation that
passed developer canonical verification and 26/26 independent oracle cases;
its provider-backed reviewer checkpoint is still open. Multi-stage evidence
aggregation, scheduling, branch promotion, notifications, merging, and
automatic archival are **not** implemented. The
intended behavior and stages are owned by the active
[`development-agent-pipeline`](../../docs/specs/active/subspecs/development-agent-pipeline.md)
specification. Detailed advisory design belongs in its
[implementation plan](../../docs/specs/active/subspecs/development-agent-pipeline-implementation-plan.md).

## Current boundary

The pipeline owns:

- creation of a bounded worker task packet, including validated A4.1 stage packets;
- required repository/spec preflight and a clean Git base;
- an immutable base commit for every Git-backed run;
- isolated worker branch/worktree preparation;
- one initial Aider execution plus at most three bounded repair attempts, either developer-triggered or via an explicitly requested `run-cycle`;
- pre-model Aider CLI/version compatibility, Git-identity, and runtime-configuration preflight evidence;
- worker stdout/stderr, exit status, changed-file list, source-change digest, HEAD identity, and log digest evidence;
- prevention of worker auto-commits in the A2.1 adapter;
- directory-scoped Gitleaks scanning of the complete worker worktree, including uncommitted and untracked changes, before canonical verification;
- redacted scanner evidence without persisting raw secret matches or scanner output;
- sequential policy-required verification through `verify` and fail-closed automatic check/review/retry orchestration through `run-cycle`;
- execution of focused and canonical verification inside the isolated worker worktree;
- immutable task/policy digests and, for A4.1 stages, advisory-plan/context digests and actual changed-path enforcement;
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
- a maximum of three repairs and an explicit allowlist of check names eligible for conservative automatic failure classification;
- conservative path-based risk hints;
- the requirement for a main-model checkpoint before acceptance.

The A2.1 adapter currently uses Aider `0.86.2` with `deepseek/deepseek-chat` by default. Provider credentials stay outside repository policy and command evidence; export provider-native variables such as `DEEPSEEK_API_KEY` in the launching environment. Pipeline execution strips `AIDER_*` variables, disables python-dotenv loading, and supplies an empty run-local `--config`, so home/repository `.aider.conf.yml` and `.env` files cannot silently change the worker contract. Ambient home/repository `.aider.model.settings.yml` or `.aider.model.metadata.json` files are rejected because Aider merges those independently of `--config`. Unsupported Aider versions or configured CLI switches fail before model access. Git `user.name` and `user.email` must already resolve so Aider cannot fill missing identity by mutating the shared repository Git configuration. The adapter disables Aider auto-commits, automatic lint/test execution, model-suggested shell commands, release-note/update prompts, URL prompts, analytics, and `.gitignore` mutation. Aider input/chat history is redirected into the run evidence directory instead of the worker tree. Pipeline verification remains responsible for command execution and evidence.

Stage A2.2 requires Gitleaks `8.30.1` to be installed on `PATH` before executing pipeline verification; installation is external and never performed by the worker. The pinned `tools/agent-pipeline/gitleaks.toml` extends Gitleaks default detection rules and excludes generated build/IDE/Git internals. The directory scan targets the whole worker filesystem rather than Git history alone, so it covers Aider's uncommitted edits and new files. It rejects worker-created `.gitleaksignore`, disables inline `gitleaks:allow` suppressions, and refuses changes to the scanner config, `pipeline-policy.json`, or `run_checks.sh` inside a worker run. Scanner version/config/source identity, exit status, finding count, rule/file/line metadata, and redacted failure reason are recorded in `checks.json`. Raw secret values, report JSON, and scanner stdout/stderr are not retained; the temporary report is deleted. Some Gitleaks releases do not create an empty report when no findings exist; zero exit status remains authoritative for a successful clean scan. Missing binary, incompatible version, malformed finding report, detected secrets, or an unsuccessful scan blocks progression. This scanner does not replace independent review or separate historical Git secret scanning.

Path-based risk is only an escalation hint. It must never be interpreted as proof that a change is safe. When Git/base-commit evidence is unavailable, risk is reported as `UNKNOWN`.

## Stage Task Packet (A4.1, implemented / verification-pending)

`MAIN_MODEL` and the developer may prepare a bounded JSON Stage Task Packet containing `stageId`, active `specPath`, optional legacy `implementationPlanPath`, `requirementIds`, `featureIds`, `owningPaths`, `editablePaths`, `contextPaths`, `riskLevel`, `delegation`, `task`, and `completionCriteria` (plus `schemaVersion: 1`). The packet must use `delegation: BOUNDED` and `riskLevel: LOW`; higher-risk or unknown-ownership stages still require human-led handling. Existing unstructured `init` remains available for legacy runs, but its `--scope` values remain **hints**, not enforced authorization.

New staged sub-specs use a matching advisory `<subspec-stem>-implementation-plan.md` file. A legacy sub-spec without a companion may use `implementationPlanPath: null`. An existing companion cannot be silently omitted. The active spec must contain the requested stage and requirement identifiers. A developer must review the packet **before** triggering it; packet fields do not grant additional authorization or replace the normative spec.

Initialize with:

```bash
python3 tools/agent-pipeline/agent_pipeline.py init-stage \
  --packet tools/agent-pipeline/examples/a4-pilot-stage-packet.json \
  --run-id my-reviewed-stage-01
python3 tools/agent-pipeline/agent_pipeline.py prepare-worker --run-id my-reviewed-stage-01
python3 tools/agent-pipeline/agent_pipeline.py run-cycle --run-id my-reviewed-stage-01
```

The controller resolves root rules, active spec, advisory companion, owning README/contract, applicable local `AGENTS.md`, and explicitly requested public cross-module context. Aider receives these files with `--read`; exact existing editable files are supplied as editable arguments. Required context and the packet are bound by SHA-256 to the run evidence, then checked against the base repository and isolated worktree before later commands. `worker.json` fails if actual tracked/untracked changes include a path outside the approved exact-file or explicit `directory/` scope, a symlink escape, or an altered read-only input. Required checks/review cannot accept that failed worker attempt. Directory authorization is rejected if it includes protected rules/spec/policy files. A stage packet cannot authorize editing an unrelated owning area by omission.

This is **edit acceptance enforcement**, not an OS filesystem or model-reading sandbox. Aider's repository map may still inspect other source files; do not use this mode to expose secrets or restrict confidential read access. The A4.1 packet does not yet infer the complete implementation plan from Markdown, verify human approval cryptographically, schedule multiple stages, or autonomously commit changes. Material implementation-plan deviations still require explicit worker explanation and developer/main-model review.

### Opt-in live A4 pilot

After applying and committing this change to a **clean checkout**, run:

```bash
bash tools/agent-pipeline/run_stage_pilot.sh
```

The committed example packet assigns Aider a genuine Python task: improve source-bound multi-attempt `handoff.json` evidence and add deterministic regression coverage. It does **not** specify a replacement diff. The script uses a new run ID, invokes the full Gitleaks/canonical/review/repair cycle, leaves results in a separate `build/agent-worktrees/<run-id>/` scratch branch, and writes `build/reports/agent-pipeline/<run-id>/change.patch`. It never commits, merges, archives or accepts the result. If the run fails, preserve its evidence and escalate rather than rerun the same ID. Actual DeepSeek API access and local Aider/Gitleaks prerequisites remain necessary.

## A4.3 read-only sub-spec evidence summary (verification-pending)

The opt-in `summarize-subspec` command combines **explicitly selected** persisted
A4.1 stage runs into one in-memory JSON report. It does not discover all runs,
assign stage order, mutate evidence, execute checks/review, start new workers,
commit, notify, or grant sub-spec acceptance. The caller must supply the exact
active sub-spec path, the *ordered expected stage roster*, and selected run IDs.
The stage roster is an assertion from the developer, **not** parsed from Markdown.

```bash
python3 tools/agent-pipeline/agent_pipeline.py summarize-subspec \
  --spec docs/specs/active/subspecs/development-agent-pipeline.md \
  --stage A4 \
  --run-id <first-existing-run-id> --run-id <second-existing-run-id>
```

The `--stage` value must match the persisted `stageId` (e.g. `A4`), not the
implementation-slice label (`A4.2a`, `A4.2b`). These are placeholders only;
select real run IDs after inspecting their manifests.

Run IDs are selected in the supplied order; they need not imply actual
chronological or Git ancestry order. A4.3 preserves repeated/blocked runs for
the same stage and reports missing expected stages rather than inventing
completion. All selected runs must have persisted `stagePacket` authority and
an intact `stage-packet.json`; legacy packetless runs cannot be included. The
reader requires consistent sub-spec and advisory plan identities, and exposes
source digest changes across the selected runs. It reuses A4.2a single-run
integrity validation and A4.2b worker-deviation collection. Present
`review-attempt.json` is checked for consistency with the latest worker source.
It rejects malformed or conflicting evidence, including symlinks.

An independent deterministic acceptance oracle can be run before live review:

```bash
python3 tools/agent-pipeline/benchmarks/subspec_evidence_oracle.py \
  --candidate tools/agent-pipeline/agent_pipeline.py
```

The report contains compact stage/run/check/reviewer/deviation metadata and
current-byte SHA-256 evidence references, without raw logs, model transcripts,
reviewer responses, or commands. Source digests are **snapshot indices**, not
cryptographic proof of historical immutability. `READY_FOR_MAIN_REVIEW` is
only a per-run state; neither the report nor a provider smoke probe proves
promotion, full planned-stage coverage, main-model approval, or developer
acceptance. This slice does not change `handoff.json` or the A5 lifecycle.


## A4.4 private evidence custody and compact sub-spec handoff (verification-pending)

`export-subspec` is an **explicit, single-shot local export** of selected A4.1
source-bound runs. It validates A4.3 evidence first, then makes a private,
replayable copy of every regular direct-child run file. This preserves
`manifest.json`, the exact `stage-packet.json`, worker attempts, checks, review,
structured deviation claims, logs and any source patch across a later Gradle
`clean`. Copies are not independently signed historical evidence: file digests
only detect changes relative to the generated inventory. Neither this command
nor the export changes reviewer, checks, Git, or developer acceptance state.

**The entire `runs/` subtree is sensitive.** It may contain raw worker logs,
secrets, source code, commands and provider output. The CLI creates its bundle
with restrictive local file permissions (private directory, owner-only files).
Do not upload, publish or attach the private directory or `bundle-index.json`
without an explicit security review. Only `review-handoff.json` is a compact,
raw-transcript-free index suitable for *developer inspection* before sharing.
It preserves explicit failed runs, missing stages, source/plan digests,
requirement/feature IDs, check/reviewer/deviation metadata and an external
acceptance flag. It does not include the exact source diff for code review;
provide that separately by an approved secure channel when needed. A4.4
has no stage-commit authority: its `promotionEvidence` is not a claimed commit.

Export **before** any `./run_checks.sh` or `./gradlew clean` that may delete
ignored `build/reports/agent-pipeline/` in the relevant checkout. The output
must be a **new absolute directory outside the repository and all build trees**,
with an existing parent directory. For example, substitute your own real run ID
and its actual `stageId` from `manifest.json`:

```bash
PRIVATE_OUT="$(mktemp -d)/evidence-a44"  # Only the parent exists; export creates this child.
python3 tools/agent-pipeline/agent_pipeline.py export-subspec \
  --spec docs/specs/active/subspecs/development-agent-pipeline.md \
  --stage A4 \
  --run-id '<actual-run-id>' \
  --output-dir "$PRIVATE_OUT"
python3 tools/agent-pipeline/agent_pipeline.py verify-subspec-export \
  --bundle-dir "$PRIVATE_OUT"
python3 tools/agent-pipeline/agent_pipeline.py \
  --runs-root "$PRIVATE_OUT/runs" summarize-subspec \
  --spec docs/specs/active/subspecs/development-agent-pipeline.md \
  --stage A4 --run-id '<actual-run-id>'
```

The export **refuses** existing destinations, symbolic links, non-regular
files, oversized or unexpected evidence shapes, missing packet bindings and
source changes during copying. If verification reports corruption, do not
reinterpret it as acceptance or reconstruct missing evidence from a summary.
Old historical runs deleted by a previous `clean` cannot be recovered by
this feature. Exported raw evidence must remain in private storage and be
managed under the developer's retention and secret-handling policy.

Focused independent custody verification:

```bash
python3 tools/agent-pipeline/benchmarks/subspec_handoff_oracle.py \
  --candidate tools/agent-pipeline/agent_pipeline.py
```

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

`run-worker` is the **initial** attempt and cannot be invoked twice for the same run. A successful Aider process that leaves no actual changed/untracked files now records worker `FAIL` with `failureReason: worker produced no source changes` and blocks verification. Do not treat model output as implementation evidence. A subsequent correction must use the explicit `repair` command; it is not an implicit retry.

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

An unexpected worker commit fails the worker result. The repository-owned adapter requests `--no-auto-commits`, so the expected A2.1 handoff is an uncommitted diff inside the isolated worktree. After `worker.json` is captured, later checks/status also reject any source drift from that recorded worker state. Each worker result is also saved as `worker-attempt-N.json`, without rewriting earlier attempts.

## Run one bounded repair (A3, verification-pending)

When the current run is blocked by an **exit-zero no-diff implementation**, a failed deterministic check, or an independent review with low-risk `CHANGES_REQUIRED` findings, the developer may explicitly authorize a repair:

```bash
python3 tools/agent-pipeline/agent_pipeline.py repair --run-id <run-id>
python3 tools/agent-pipeline/agent_pipeline.py verify --run-id <run-id>
python3 tools/agent-pipeline/agent_pipeline.py run-review --run-id <run-id>
python3 tools/agent-pipeline/agent_pipeline.py handoff --run-id <run-id>
```

`repair` reuses the isolated worktree **only when its previous worker source digest and Git HEAD are unchanged**. At most **three** repair attempts are allowed after the initial attempt, controlled by `repairExecution.maxRepairAttempts` (max `3`) in the repository policy. The repair task includes the original task plus bounded feedback identifying the failing check/exit status, missing diff, or reviewer finding IDs/categories/severities; raw security matches and unfiltered check logs are never sent to the model. Manual `repair` remains available for a developer-authorized recovery. The opt-in `run-cycle` command described below performs conservative automatic classification and repeated checks/review.

The current repair gate permits only LOW changed-path risk and refuses a Gitleaks failure, high/critical or escalation-category finding, a failed reviewer request, unexpected worker commit, nonzero worker exit, missing source evidence, or source drift. Infrastructure failures and ambiguous requirements must be escalated rather than converted into code edits. Existing changed paths remain **scope hints** until the A4 stage packet/enforced scope is implemented: inspect the diff and do not use this command to authorize new module boundaries.

Before a repair starts, the latest `worker.json`, checks, review, status and handoff evidence are retained in attempt-specific snapshots. The new attempt gets a fresh `worker.json`, empty check results, and no review, so old passes cannot be reused. After changes, **rerun all required checks and fresh independent review**. An independent review is single-use for a source attempt; adding checks after review requires a new repair round. If any gate refuses a repair, stop and involve the developer. No stage or spec is automatically accepted, merged, or committed.

## Run a bounded automatic cycle (A3.2, verification-pending)

After `init` and `prepare-worker` on a **new** run without worker, check, or review attempts, invoke:

```bash
python3 tools/agent-pipeline/agent_pipeline.py run-cycle --run-id <run-id>
```

`run-cycle` executes one initial Aider attempt, scans for secrets with Gitleaks, runs the configured canonical checks, invokes a fresh-context independent reviewer, and writes `handoff.json` only when the final source reaches `READY_FOR_MAIN_REVIEW`. It may trigger at most three subsequent Aider repair attempts. Each repair invalidates previous checks and review, which are rerun in policy order; check logs are unique per worker attempt so archived digests remain valid. The operation is opt-in and **single-use per run**: `cycle.json` persists step/attempt/status/reason-code evidence. After a crash or escalation, a second `run-cycle` is refused; preserve the run for developer inspection rather than automatically resuming partial work.

A repair that produces no new diff compared with the previous worker attempt is rejected rather than counting as implementation progress. Automatic repair is allowed for a successful zero-diff Aider attempt; a low-risk, non-escalating `CHANGES_REQUIRED` reviewer verdict; or an explicitly allowlisted required check with verifiable logs showing a recognized Java compile/test failure or a confirmed Python unittest failure from the canonical pipeline test step. `repairExecution.automaticCheckRepairNames` lists eligible non-security checks (currently `canonical`). A matching failure is not considered repairable when the log also indicates infrastructure/network/container/toolchain trouble, or the report is missing, changed, or ambiguous. Gitleaks detection, unexpected commits, source drift, provider/schema errors, unknown check failures, higher-risk changed paths, and escalation findings stop the automatic cycle. Python unittest classification requires the canonical verification summary to identify only the agent-pipeline test step as failed and validates the actual unittest error sections. Simulated infrastructure messages printed by successful test fixtures are not treated as active failures; infrastructure indicators inside a failing traceback still cause escalation. Repair feedback may contain a bounded list of sanitized failing test names and exception types, but never raw scanner output, exception messages, or arbitrary check logs.

This A3.2 heuristic is intentionally narrow; it does **not** provide semantic failure understanding, a stage scope sandbox, or automatic authorization for changes outside the approved task. Policy-enforced stage task packets and editable-scope restrictions are A4 work. `READY_FOR_MAIN_REVIEW` is only a handoff state; there are no automated stage commits, merges, notifications, spec archival, or acceptance decisions.

## Developer-led MAIN_MODEL recovery after a terminal run

A `BLOCKED` status or terminal `ESCALATED` `cycle.json` does **not** authorize
resuming the same `run-cycle`, accepting its source, or merging its scratch
`agent/<run-id>` branch. `READY_FOR_MAIN_REVIEW` also remains a checkpoint,
not acceptance. Preserve the terminal run ID, worktree, and evidence before
starting a developer-led recovery.

The supported manual recovery sequence is:

1. **Preserve and export evidence.** Retain `cycle.json`, `manifest.json`,
   `worker.json`, all `worker-attempt-*.json`, `checks.json`, `status.json`,
   `review.json` or `review-attempt.json` when present, the exact `change.patch`,
   and independent benchmark reports when present. Share a minimal `review.zip`
   without raw model responses, scanner matches, credentials, or unreviewed logs.
   Inspect the archive before sharing. A structured archive may omit the failed
   check's detailed traceback; provide a *redacted* relevant check-log excerpt
   separately when necessary. An absent reviewer verdict is not a PASS.
2. **Refresh repository context.** After committing any independently approved
   changes to the developer branch, regenerate `signalharvester_files.txt` and
   replace the previous ChatGPT Project Source. Record its commit/dirty state
   and compare it with the failed run's `manifest.json` base revision. The
   concatenated snapshot is an indexed repository view, not a worker patch.
   The `review.zip` contains the proposed unaccepted source delta. If the
   latest snapshot does not contain the actual run base, provide the missing
   intervening commit diff or refresh the snapshot from the correct base; do
   not silently overlay stale file content on a newer repository.
3. **Request MAIN_MODEL recovery.** The developer asks ChatGPT to read the
   current repository rules, active sub-spec and advisory companion, source
   delta, attempt/check/review evidence, and applicable tests. Distinguish
   `WORKER_MODEL` code defects from test-fixture defects, orchestrator
   classification mistakes, provider/reviewer failures, and specification
   ambiguities. Review the complete permitted source delta for correctness,
   architecture, evidence binding, security, compatibility, and test coverage.
   Reproduce failures and independently challenge model-authored tests.
4. **Prepare an explicit corrected patch.** `MAIN_MODEL` may implement
   bounded fixes to the worker result and relevant controller/docs/tests,
   while respecting the same repository authorities and developer-approved
   scope. Escalate any new high-risk/unknown ownership rather than expanding
   it silently. Deliver a ZIP containing only changed/added files relative
   to an identified target base, list any deletions separately, and report
   actual validation versus unverified checks. Do not treat a successful
   independent benchmark alone as complete acceptance.
5. **Verify and decide.** The developer applies the corrected patch to a new
   feature branch at the verified base, reviews the diff, and runs focused
   regression/contract checks plus `./run_checks.sh` and any required rare or
   stage-specific checks. Because source changed, the failed run's checks and
   independent reviewer verdicts are historical evidence only; obtain fresh
   review for the corrected source state. The developer explicitly decides
   whether to accept the bounded stage and proceed to the next sub-spec stage.
   Sub-spec acceptance and paired archival still follow `docs/specs/README.md`.

**Recovery is developer-directed, not a new automatic retry mode.** Do not
re-enter a terminal `RUN_ID`, overwrite preserved attempt evidence, cherry-pick
unverified worker edits without review, or auto-merge/auto-archive. After
accepted fixes are committed, refresh the Project Source again; use the new
snapshot plus current stage-specific evidence rather than old patch chains.

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

## Planned model-role and implementation-plan integration (A4+)

The root `AGENTS.md` defines `MAIN_MODEL`, `WORKER_MODEL`, `REVIEWER_MODEL`, `ORCHESTRATOR`, and `DEVELOPER`. The active specification defines how those roles interact; the roles are not tied to a particular model brand.

For a newly authored staged sub-spec, `MAIN_MODEL` prepares a same-directory `<subspec-stem>-implementation-plan.md`. It contains non-binding architecture and per-stage file/context suggestions. The owning sub-spec remains normative for requirements and known module scope; the companion must not authorize edits, redefine checks, or replace local `AGENTS.md` and module contracts. A worker may propose a better in-scope approach, recording material deviations and escalating new ownership or risk.

A4 should validate and source-bind the sub-spec and companion plan separately, resolve current repository context, pass the plan to Aider **read-only**, and enforce approved editable paths outside the prompt. Current `init`/`prepare-worker`/`run-worker`/`run-cycle` commands do **not** discover companion plans, enforce a stage task packet, or run an entire sub-spec based on these documents. Do not infer this functionality from a plan existing in the repository.

After end-to-end implementation the developer initiates a final `MAIN_MODEL` review through ChatGPT; neither the reviewer adapter nor `handoff` automatically initiates ChatGPT review. Only explicit developer acceptance can authorize paired archival of the sub-spec and companion plan. Current pipeline commands do not perform lifecycle moves.

## Specification and next stages

The active [`development-agent-pipeline`](../../docs/specs/active/subspecs/development-agent-pipeline.md) specification owns intended evolution, stage sequencing, and final acceptance criteria. This guide owns current implemented commands and evidence semantics only.

A2.1–A2.3 and A3.1/A3.2 remain developer-verification-pending. The first real documentation pilot reached DeepSeek but applied no changes after edit-format mismatches. Next: rerun P2 with the opt-in `run-cycle` on real Aider/Gitleaks/reviewer infrastructure, then develop A4 delegation packets and enforced scope. Multi-stage scheduling, feature-branch progression, notifications, and lifecycle gates remain planned, **not implemented by these commands**.


## Automatic independent review and main checkpoint handoff (A2.3)

After `verify` passes, invoke the read-only reviewer from a fresh provider request:

```bash
python3 tools/agent-pipeline/agent_pipeline.py run-review --run-id <run-id>
python3 tools/agent-pipeline/agent_pipeline.py handoff --run-id <run-id>
```

The reviewer uses the policy-owned HTTPS DeepSeek chat-completions endpoint and model, with `DEEPSEEK_API_KEY` supplied by the environment. It receives only the immutable task packet, bounded worker source diff (including untracked files), changed-file names, and check statuses. Worker chat transcripts are not forwarded. A new independent request is created for each run, and its answer must match the repository's structured review schema. The worker cannot run or accept its own review. Review attempts are one-shot; provider/schema failures are recorded as `review-attempt.json` without saving raw API responses and block the run. Failed attempts now include a sanitized `reasonCode` (`HTTP_STATUS_FAILURE`, `REQUEST_TIMEOUT`, `TRANSPORT_FAILURE`, `INVALID_RESPONSE_ENVELOPE`, `INVALID_REVIEW_JSON`, `INVALID_REVIEW_SCHEMA`, or `REVIEW_EVIDENCE_FAILURE`) and optional numeric `httpStatus` for HTTP errors. These codes diagnose which boundary failed; they do not constitute a reviewer verdict, reveal raw provider output, or authorize retrying a terminal run. A larger diff is rejected rather than silently truncated. Never send sensitive code to an unapproved provider.

### Reviewer response diagnostics and transport probe

The A2.3 reviewer adapter uses DeepSeek JSON-object mode and a concrete v1
response example. The provider returns `schemaVersion`, `verdict`, and `findings`;
reviewer identity is injected by the trusted adapter. The adapter still
requires full `validate_review()` success, and never treats an incomplete
completion (`finish_reason` other than `stop`) as a successful review.

For a **synthetic, opt-in provider transport check** that does not start Aider,
read actual repository code, create a run, or grant acceptance:

```bash
python3 tools/agent-pipeline/agent_pipeline.py probe-reviewer
```

This command requires the normal `DEEPSEEK_API_KEY` environment variable and
makes one billable request to the policy-approved endpoint. It prints only
bounded structured diagnostics (`probeOnly`, `status`, optional `verdict` and
`findingsCount`). A successful probe proves JSON adapter compatibility for
synthetic input only; a real source-bound run still needs all normal gates and
a fresh independent reviewer verdict.

On provider errors, `review-attempt.json` retains the existing sanitized
`reasonCode`. A failure in review schema validation can additionally retain
`schemaFailureCode` (`SCHEMA_VERSION`, `VERDICT`, `FINDINGS_ARRAY`,
`FINDING_OBJECT`, `FINDING_ID`, `FINDING_SEVERITY`, `FINDING_CATEGORY`,
`FINDING_SUMMARY`, `FINDING_STATUS`, or `OTHER`). Provider completion metadata
is reduced to `completionCode` (`STOP`, `LENGTH`, `CONTENT_FILTER`, or `OTHER`).
No rejected JSON fields, findings, raw responses, error messages, or API
credentials are recorded in these diagnostics. A provider/schema failure
remains `BLOCKED`, and a terminal run is never resumed.

`handoff.json` summarizes task/spec/base identity, worker change digest/files, required checks, independent review findings, status/risk and evidence paths. It is an index for the main-model checkpoint, not acceptance or proof that every review issue is resolved. `record-review` remains available for externally supplied independent evidence, but it must not be used as a worker self-review. Neither command merges branches, repairs code, or archives specifications.
