---
type: Implementation Guide
title: State-driven agent pipeline orchestration implementation plan
description: Advisory file-by-file blueprint, failure semantics and independent verification scenarios for A6.2 through A6.6.
---
# State-driven agent pipeline orchestration — implementation plan

This companion is **advisory**, paired with the normative [State-driven Agent Pipeline Orchestration sub-spec](development-agent-pipeline-stateful-orchestration.md). Follow its SO1–SO11 requirements and the owning [Agent Pipeline](development-agent-pipeline.md) AP1–AP20 contracts. A recommendation here never grants execution, scope enlargement or acceptance. `MAIN_MODEL` prepares the stage; the `WORKER_MODEL` only implements the developer-approved LOW/BOUNDED A6.2 packet. `REVIEWER_MODEL` remains a separate model. Tests verify requirements, not conformity to illustrative pseudocode.

## Before any stage: inspect source and establish the baseline

In order, read root `README.md`, `AGENTS.md`, `docs/specs/README.md`, the platform spec, both Agent Pipeline sub-specs and their paired plans, nearest `AGENTS.md` (if present), `tools/agent-pipeline/README.md`, `docs/TESTS.md`, owning controller/validator/policy/packet implementation, and existing focused tests. Inspect source before choosing helper APIs. Existing entry points at planning time:

- `tools/agent-pipeline/agent_pipeline.py`: `build_parser`, `main`, `validate_stage_packet`, `stage_context`, `run_git`, `read_json`, `sha256_file`, `drive_stage_sequence`, `plan_stage_batch`.
- `tools/specs/validate_state.py`: `validate_state(source=..., repo_root=...)` using `docs/specs/state.schema.json`. It already rejects `RUNNING`/`ACCEPTED` and duplicate STATE JSON keys. The module's default constants point to its checked-in repo; **do not** call an unrelated installation's validator when testing a fixture repository.
- `tools/agent-pipeline/run_aider_stage.sh`: wrapper selecting the current Aider adapter; **not** an autonomous scheduler.
- `tools/agent-pipeline/pipeline-policy.json`: executor/model/reviewer credentials and verification policy; `tools/agent-pipeline/orchestrator-config.json`: new reviewed selection mapping and one-stage ceiling; `docs/specs/STATE.json`: developer-owned checkpoint.
- `tools/agent-pipeline/tests/test_agent_pipeline.py`: existing CLI, packet, source-bound preflight, and `drive-stage` tests; `tools/specs/tests/test_validate_state.py`: strict STATE contract; existing independent A5/A6.1 oracles.

At the snapshot used to write this design (`main`, `08379ceebe2891e92daa4c06e821ab2f61688b4c`), `STATE.json` still selects `development-agent-pipeline.md / A5.4a / DESIGN`. **Do not edit it in an active run or silently reinterpret it as A6.2.** The developer must first finish/reconcile any outstanding acceptance work, then commit a deliberate STATE checkpoint selecting the new active spec/A6.2. Any newer Git source changes must be reconciled before running the worker; old snapshot evidence is not permission to overwrite files.

### Single-stage operational contract (currently implemented tools)

1. Developer creates/reuses **one `feature/*` branch for spec and implementation**, applies spec/plan + reviewed packet/config, runs checks, manually commits/pushes approved design baseline. Do not create one feature branch per stage.
2. At a separate clean developer-controlled Git checkpoint, change selected `STATE.json` to `specPath=docs/specs/active/subspecs/development-agent-pipeline-stateful-orchestration.md`, `stageId=A6.2`, `phase=READY_TO_DELEGATE`, `runId=null`, `developerDecision=UNRECORDED`; validate via `python3 tools/specs/validate_state.py`, review and **manually commit**. This is not acceptance of A5.4a/A6.1.
3. Developer reviews `tools/agent-pipeline/examples/a62-state-resolver-stage-packet.json`, runs `init-stage` to bind its exact committed bytes and a fresh run ID, then uses `drive-stage --execute --developer-approved --stage A6.2` (one-entry ordered roster). The current controller does **not** discover packets from STATE; A6.2 implements the **read-only** discovery command for later use.
4. ORCHESTRATOR preflights Git identity, tool/provider policy, read-only context SHA, Aider compatibility; creates isolated `agent/<run-id>` and worktree, invokes Aider with no auto-commit; runs required Gitleaks and canonical checks, then invokes independent reviewer and bounded repair (one initial attempt plus at most 3 repairs, subject to existing policy). `READY_FOR_MAIN_REVIEW` or `AWAIT_DEVELOPER_PROMOTION` is **not acceptance**.
5. Worker edits only the two approved Python files. Any attempt to edit STATE, its schema/validator, config, packet, spec/plan, security policy, or unapproved tests is out of scope and must fail closed. Escalate unresolved architecture, changed-risk assumptions, security findings, provider outage, corrupt evidence, or exhausted repairs.
6. Developer reviews worker diff and evidence in ChatGPT `REVIEW`, confirms checks and correct tests, then separately authorizes guarded `promote-stage` with the exact expected parent SHA. Promote has a separate Git CAS receipt; Aider never commits and the controller does not auto-push, merge or archive. Resume after interruption requires evidence reconciliation, not replay of the same run ID.
7. Developer inspects the resulting branch, obtains the full required global gate/CI and final MAIN_MODEL review before manual merge; future stages are started only from new committed source-bound checkpoints.

## A6.2 — detailed worker implementation blueprint

**Normative IDs:** SO1, SO2, SO3, SO4, SO6, SO7, SO9, SO10, SO11. **Risk:** LOW/BOUNDED **only while implementation is read-only**. If code must change Git promotion, schema, runtime journal or approval semantics, stop and return a design escalation.

### File-by-file work guidance

| Existing / new path | Status | Action |
|---|---|---|
| `tools/agent-pipeline/agent_pipeline.py` | **Worker editable, existing** | Add `resolve-state` command and pure validation helpers. Integrate with existing argument parser and `main`; no new runtime writes. Reuse `validate_stage_packet` for packet contracts and existing source/Git helpers for identity. Preserve current CLI output/semantics. |
| `tools/agent-pipeline/tests/test_agent_pipeline.py` | **Worker editable, existing** | Add fixture-based tests using temporary real Git repositories for SR-01…SR-14; use mocks only for observability of forbidden calls and assert bytes/refs/dirs unchanged. Keep existing fixtures and independent oracles untouched. |
| `tools/agent-pipeline/orchestrator-config.json` | **Developer-owned new baseline; worker read-only** | v1 strict stage mapping and ceiling; do not let worker edit it or silently infer missing packets. |
| `tools/agent-pipeline/examples/a62-state-resolver-stage-packet.json` | **Developer-owned new baseline; worker read-only** | Exact one-stage LOW packet; complete criteria and read-only context. |
| `tools/specs/validate_state.py`, `docs/specs/STATE.json`, `docs/specs/state.schema.json` | **Protected existing** | Read and validate only. Do not duplicate/change state regexes or relax phase rules. |
| Spec and plan files, root `AGENTS.md`, `pipeline-policy.json`, `reviewer-endpoints.json`, `run_checks.sh` | **Protected existing** | No worker edits; changes need new developer-led baseline. |

**Important:** the new orchestrator config is a control-plane file. Add `tools/agent-pipeline/orchestrator-config.json` to `PROTECTED_WORKER_CONTROL_PATHS` in the same controller change (using the already authorized editable `agent_pipeline.py`). The stage packet's context SHA also provides per-run read-only protection. This addition MUST have a negative regression test proving a later packet cannot authorize edits to the config, including through a directory-scope allowlist; do not make it only a prompt instruction.

### Recommended resolver behavior (illustrative algorithm, not existing code)

```text
resolve-state --config <optional reviewed repo-relative config path>
    require git repo root, clean named feature/* checkout, exact HEAD
    load config as JSON with duplicate-key rejection
    verify non-symlink regular file, under repo; exact schema v1 keys and 1-stage ceiling
    check unique mapping keys, safe repo-relative packet paths and no path escapes
    load STATE via repository-owned validate_state(repo_root=repo_root, source=STATE)
    require selected phase READY_TO_DELEGATE, runId null, developerDecision UNRECORDED
    require exactly one mapping for (selected specPath, selected stageId)
    load packet; validate_stage_packet(repo_root, packet)
    require packet.specPath, stageId, plan exactly match approved selection
    compute committed config SHA, packet SHA and exact Git HEAD
    check no observed bytes, Git status or HEAD changed during preflight
    emit one JSON READY report with explicit executionSupported=false
```

A possible implementation is to import the repository's `tools/specs/validate_state.py` by its explicit file path, call `validate_state(repo_root=repo_root, source=repo_root/'docs/specs/STATE.json')`, and validate the config independently using the same duplicate-key parser. **Do not** rely on `PYTHONPATH` or the default validator's hard-coded root when validating a temporary fixture repository. Tests must prove the validator is called on the fixture's own contents, not on the developer checkout. Use no new external Python dependencies.

The resolver should include `sourceHead` only if Git returns a trustworthy full SHA (SHA-1 currently supported by this repository). Reject detached HEAD, other branch namespaces and dirty/untracked changes using established A5 preflight patterns. Use stable JSON `sort_keys=True`; nonzero on any error with sanitized stderr; do not write a status file just to describe a read-only failure. Avoid logging full stage packet task text.

A6.2 MUST reject config root keys other than `{schemaVersion, executionMode, maxStagesPerInvocation, stagePackets}` and mapping keys other than `{specPath, stageId, packetPath}`, including duplicates. A path with `..`, leading slash, symlink, NUL, empty segment or out-of-repository resolved target is invalid. Validate the exact packet via current packet validator, not a hand-maintained copy. If checking tracked/committed bytes needs additional Git identity logic, use fail-closed source checks, never mutate refs. The CLI MUST not expose `--execute` or automatically call `init-stage`.

### Deterministic verification matrix for A6.2

All scenario IDs below are **acceptance test obligations independently derived from SO1–SO11**. The worker should implement focused tests, but the developer/MAIN_MODEL must also assess them independently and retain source-bound evidence.

| ID | Fixture / action | Expected observation |
|---|---|---|
| SR-01 | Real temporary Git repo on clean `feature/state-resolver`; active spec/plan, valid STATE READY_TO_DELEGATE, exact approved config/packet | Exit 0; stable JSON READY with exact spec/stage/packet/plan/HEAD/hash, explicit non-execution flags |
| SR-02 | Repeat SR-01 twice without changes | JSON and Git/evidence/file bytes identical; no new files or run IDs |
| SR-03 | STATE selects `A5.4a` but config maps only `A6.2` | Nonzero, no fallback or guessed packet |
| SR-04 | STATE phase DESIGN, BLOCKED, PAUSED, READY_FOR_REVIEW or contains runId | Nonzero; READY never claimed; no writes |
| SR-05 | Change STATE to duplicate keys, invalid JSON, wrong selected workstream or two active entries | Nonzero with repository validator semantics; no partial response claiming READY |
| SR-06 | Config has duplicate JSON key, duplicate `(specPath,stageId)` mapping, unknown field or invalid `executionMode` | Nonzero; no implicit merge/last-key-wins |
| SR-07 | Config has `maxStagesPerInvocation=0`, `2`, null, boolean or numeric string | Nonzero, ceiling cannot be bypassed |
| SR-08 | Packet stage/spec/plan differs, missing companion, invalid LOW/BOUNDED values, or unknown requirement | Nonzero by actual Stage Task Packet contract |
| SR-09 | Config/packet is symlink, absolute path, `../` escape or malicious relative path | Nonzero before reading outside repo |
| SR-10 | Clean base turns dirty (tracked or untracked), detached HEAD, `main`, or wrong feature branch | Nonzero; no Git/worktree mutation |
| SR-11 | Mutate source/config/packet bytes between preflight identity and report (fixture controlled hook) | Nonzero; never report stale source as READY |
| SR-12 | Mock/spying on worker/reviewer/check runners, filesystem writes and `git update-ref` | Zero invocations on success and all failure scenarios |
| SR-13 | Later stage packet tries to authorize `orchestrator-config.json` via direct path or `tools/agent-pipeline/` directory scope | Rejected by protected-path enforcement; no model call |
| SR-14 | Existing `init-stage`, `drive-stage`, `plan-batch`, `promote-stage` suites and independent oracles | No regression in source binding, approval, Git CAS or statuses |

**Test independence:** SR-01/03/04/06/07/08/09/10/13 are critical positive/negative gates and should have independent fixture evidence. A reviewer must verify scenarios fail on the original corresponding missing guard, not merely that a mock was called. Never adjust expected results to fit implementation. A fake provider must never use real credentials/network.

**Focused commands after worker changes (inside isolated worktree):**

```bash
python3 -B -m unittest discover -s tools/agent-pipeline/tests -p 'test_*.py' -v
python3 -B -m unittest discover -s tools/specs/tests -p 'test_*.py' -v
python3 tools/specs/validate_state.py
python3 tools/agent-pipeline/benchmarks/stage_sequence_oracle.py --candidate tools/agent-pipeline/agent_pipeline.py
python3 tools/agent-pipeline/benchmarks/batch_plan_oracle.py --candidate tools/agent-pipeline/agent_pipeline.py
./run_checks.sh
```

The exact independent oracle filenames/flags MUST be checked against the current repository before execution; the orchestrator's policy-owned canonical checks have precedence. Gitleaks and source/scope/review checks MUST also pass. If an existing oracle is unavailable, disclose the gap and stop rather than claiming an independent PASS. Integration test failures require root-cause classification; never weaken valid tests. SR-11 failure-injection and source-bound artifacts are retained privately, not uploaded as raw model logs.

## A6.3 — planned state transition and recovery design (HIGH risk; no worker packet)

**Entry gate:** A6.2 reviewed and promoted on clean Git base; developer-approved reconciliation design covering authoritative checkpoint and runtime evidence. Do not start as a LOW task.

**Candidate files, not yet authorized:** `tools/agent-pipeline/agent_pipeline.py`, a new private journal/storage module under `tools/agent-pipeline/`, `docs/specs/state.schema.json` and `tools/specs/validate_state.py` only if a reviewed state schema migration is necessary; new focused tests and a separate independent fault-injection oracle. No new dependency without approval.

Recommended transaction:

1. Persist immutable `checkpoint-intent.json` containing expected STATE SHA, expected feature-ref HEAD, target stage/phase, approved transition and exact promotion receipts; fsync/write through safe atomic replace in private custody (not inside an active worker worktree).
2. Verify consumed run identity and validated source-bound promotion receipt. Independently query the actual Git ref: distinguish *not committed*, *committed matching intent*, *commit exists but receipt missing*, and *conflicting/unknown state*.
3. Only at the **between-run** developer-controlled checkpoint, write a proposed new STATE with full structural and semantic validation. Validate it again after atomic replacement; never edit while an active source-bound run is in progress.
4. Bind the corresponding STATE update to a separately authorized Git checkpoint with exact expected parent identity. Git and filesystem are not one transaction; persist the receipt after verifying actual Git state.
5. On restart, reconcile intent/STATE hash/ref/receipt without replaying a worker call or consumed run ID; when the outcome is ambiguous, preserve evidence and return a typed escalation.

**CP failure matrix (each case must prove no duplicate execution):** CP-01 before intent; CP-02 after intent; CP-03 before promotion CAS; CP-04 after CAS/before receipt; CP-05 after receipt/before STATE write; CP-06 during atomic STATE replace; CP-07 after STATE write/before checkpoint commit; CP-08 after checkpoint commit/before receipt; CP-09 stale competing ref; CP-10 malformed/forged receipt; CP-11 simultaneous writer; CP-12 interrupted repeat reconciliation. Assert exact Git refs, STATE bytes, journal identities and no model/reviewer calls on recovery. `ACCEPTED` remains external to schema v1.

## A6.4 — planned one-stage-to-batch configuration and budgets (no worker packet)

**Entry gate:** accepted A6.3 recovery evidence, explicit batch authority contract, bounded real-provider usage accounting, pilot results. Reuse source-bound A6.1 `plan-batch`, never turn its `budget.enforced=false` into evidence of actual enforcement.

**Candidate files:** reviewed version-2 `orchestrator-config.json`, controller plus isolated scheduler/journal unit, test suite, benchmark, owning guide. New v2 config could define `maxStagesPerBatch: 1..5`, `maxProviderRequests`, `maxTotalAttempts`, `maxCostCents` and `batchApproval` binding. No independent config field may bypass A3 maxRepairAttempts or provider/security policy. `concurrency=1` per repo.

For each stage: confirm approved packet/source/checkpoint → consume unique run ID/intent → worker → required checks → independent reviewer → bounded repair or fail closed → verify exact final digest → guarded stage commit via previously approved batch authority → durable receipt → verify next exact base. Persist cumulative usage before admitting new requests; unknown usage blocks a hard cost-guarded batch, not silently counts as zero. Explicit batch approval must include exact stage roster, source/spec/plan/packet digests, editable boundaries, limits, Git branch and stopping rules. `maxStagesPerBatch>1` is disabled until two-stage failure-injection pilot passes. No automatic push/merge/acceptance.

**BB scenarios:** BB-01 one stage only; BB-02 explicit two-stage pilot; BB-03 missing/duplicated stage; BB-04 exact-parent drift; BB-05 early model failure; BB-06 reviewer escalation; BB-07 repair budget exhausted; BB-08 provider request ceiling; BB-09 cost unknown; BB-10 crash between stage commit and next preparation; BB-11 simultaneous repo writers; BB-12 packet mutation after batch approval. Every failure ends with explicit terminal evidence and no unauthorized next stage.

## A6.5 — planned diagnostics and notification delivery (no worker packet)

**Candidate files:** isolated reporter/notifier under `tools/agent-pipeline/`, config additions and deterministic delivery tests. Use existing [`tools/agent-pipeline/README.md`](../../../../tools/agent-pipeline/README.md) and `docs/DIAGNOSTIC.md` *if present* as owning operator docs; no new external transport dependency until approved.

Persist a `batch-report.json` before delivery: spec/stage identities, source/branch/commit receipts, completed/pending stages, attempts and checks, reviewer verdict and finding IDs, failure reason code, usage values or `UNKNOWN`, private log/evidence locations, recommended human action. Create a durable outbox event with unique `eventId`; delivery is a separate bounded idempotence-aware process. Begin with local console/file report, add external transports only with security and privacy review. No raw source, prompts, secrets or transcript in external messages.

**NT scenarios:** NT-01 completion; NT-02 blocked tests; NT-03 reviewer escalation; NT-04 retries exhausted; NT-05 interrupted uncertain stage; NT-06 transport unavailable; NT-07 crash after delivery/before acknowledgment (duplicate `eventId`); NT-08 redaction and private-path handling. Terminal result must remain correct when notification fails.

## A6.6 — planned developer acceptance and paired archive (HIGH risk; no worker packet)

**Candidate files:** lifecycle helper(s), state validator/schema if strictly needed, spec/archive path checks and independent Git fixture tests. Require explicit developer approval and current source-bound final `MAIN_MODEL` review, canonical/CI evidence and complete stage roster receipts. Treat acceptance as a separate external decision with auditable identity; do not write `ACCEPTED` to v1 STATE.

**AR scenarios:** AR-01 accepted pair moves together; AR-02 missing plan; AR-03 duplicate active/archive; AR-04 no acceptance receipt; AR-05 stale merge base; AR-06 interrupted archive before commit; AR-07 conflicted STATE transition; AR-08 repeated operation is safe/no duplicate archive. Inspect stable owning docs, feature branch and Git status before manual merge; the tool cannot merge automatically.

## Definition of ready for each worker stage

- Current repository source/spec/plan and STATE checkpoint are developer-reviewed and committed; no pending competing active run; Git branch is clean and appropriate.
- Exact stage is named in the active normative spec; the matching same-directory implementation plan and **one reviewed packet** exist and pass current validator.
- LOW/BOUNDED risk is proved for editable paths; high-risk unknowns stay `UNRESOLVED` and block worker launch.
- Positive/negative/boundary/regression tests are defined independently; required focused and canonical commands are available.
- The developer has explicitly authorized one source-bound execution. No notification, worker PASS, checkbox or STATE phase alone is approval.

## Definition of complete and handoff

Stage completion requires inspected diff within scope, required checks, bounded reviewer PASS without unresolved blocking findings, verified immutable evidence, and an explicit external decision concerning Git promotion. The entire sub-spec additionally requires global verification, MAIN_MODEL final review and developer acceptance. Report all unverified paths honestly, preserve consumed runs, and never update STATE, accept, or archive solely to obtain a green status.
