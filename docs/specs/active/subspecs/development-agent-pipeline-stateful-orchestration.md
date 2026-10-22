
---
type: Specification
title: State-driven agent pipeline orchestration
description: On-hold source-bound stage resolution and planned configured execution with developer-controlled lifecycle checkpoints.
document_role: subspec
spec_status: ON_HOLD
parent: ../spec-signal-harvester-platform.md
---
# State-driven agent pipeline orchestration

## Status, ownership and normative references

**ON_HOLD:** A6.2–A6.6 delegation work is deferred while the application workstream is selected. The A6.2 worker attempt was not accepted or promoted; no previous run ID may be reused. This hold is not archival or acceptance. The paired implementation plan remains advisory and on hold with this specification.

**Original design baseline; no stage in this sub-spec is accepted.** This sub-spec refines the planned A6 work of [Development Agent Pipeline](development-agent-pipeline.md), which retains the existing AP1–AP20 security, role, evidence, Git and acceptance contracts. In any conflict, stop for `MAIN_MODEL`/developer reconciliation; do not silently relax those contracts. The paired [implementation plan](development-agent-pipeline-stateful-orchestration-implementation-plan.md) is advisory only.

- Owning area: `tools/agent-pipeline/` and its existing Python controller.
- Processing-state owner: `docs/specs/STATE.json`, its schema and `tools/specs/validate_state.py` (protected control files).
- Source of truth for runnable tasks: developer-reviewed Stage Task Packets bound to an active spec and advisory plan; spec prose or STATE alone never grants execution authority.
- No product Feature ID: this is contributor tooling, not a new application capability.
- Cross-area public context: `AGENTS.md`, `docs/specs/README.md`, `docs/TESTS.md`, `tools/agent-pipeline/README.md`, existing active Agent Pipeline sub-spec and stage evidence interfaces.
- Unresolved or high-risk scope, new runner credentials, and changes to Git promotion/state mutation contracts MUST NOT be delegated as `LOW/BOUNDED` work. Such stages require a separate developer-approved architecture/risk checkpoint.

## Goal and explicitly deferred scope

Provide a deterministic, compact, repository-local Python orchestrator which eventually selects one approved stage from the validated Git `STATE.json`, executes bounded sequential worker stages, recovers after interruption, synchronizes state at safe Git checkpoints, produces actionable terminal diagnostics, and archives an accepted sub-spec with its companion. A future external binary or third-party orchestrator is out of scope. The implemented worker executor remains Aider; vendor/model selection is owned by reviewed pipeline policy.

**Current rollout limit:** at most **one stage per orchestrator invocation**, no automatic `STATE.json` update and no unattended Git promotion. The first stage, **A6.2**, is a pure read-only resolver; it does not launch a worker. The developer still explicitly invokes the existing `init-stage` / `drive-stage` commands for that stage, checks stage evidence and authorizes `promote-stage` separately. Other stages are future plans, not work implicitly authorized by this spec or its configuration.

Target operation (future, not existing command): `orchestrator run-from-state --repo-root ...` starts only after checked/approved packet and source-bound preflight. All execution permission MUST be explicit and narrower than the currently supported repository policy. `docs/specs/STATE.json` is a **between-run Git checkpoint**, never a live execution log or an acceptance receipt.

## Invariants applying to all stages

- **SO1 — State selection and authority.** The resolver MUST select exactly one workstream by `selectedWorkstreamId` from a state validated using the repository-owned schema and semantic validator. It MUST use that record's exact `specPath` and `stageId`; no regex over arbitrary spec prose, README `current_focus`, LLM inference or unreviewed fallback may select work. `SINGLE` mode only; unresolved/ambiguous state fails closed.
- **SO2 — Stage packet binding.** The stage MUST be associated with exactly one explicit, repository-tracked, developer-reviewed Stage Task Packet from an allowlisted configuration. The packet's `specPath`/`stageId`, required implementation plan, requirements, owning paths, LOW/BOUNDED scope and source digests MUST be verified against the selected state and repository contents before a run can be initialized. The mapping file does not grant execution permission.
- **SO3 — Source and configuration integrity.** Every execution preflight MUST require a clean named `feature/*` checkout, matching source identity, safe repo-relative regular files (no symlinks or directory escapes), and no unauthorized config/policy drift. Reject stale/dirty/ambiguous bases and changed bindings before any worker execution or Git mutation. Follow existing A4/A5 checks; never weaken them.
- **SO4 — Independent execution boundaries.** `WORKER_MODEL` MUST NOT edit `STATE.json`, its schema/validator, config and security policies or stage approvals. Only the deterministic orchestrator may propose a state transition, and developer-controlled Git checkpoints authorize its persistence. Worker success, reviewer PASS, stage commit and sub-spec acceptance are different facts. No worker self-approval, automatic push/merge, or auto-archival.
- **SO5 — Private evidence and replay safety.** Durable per-attempt evidence and terminal decisions MUST be stored outside Git, with checksums, exact source/packet/plan binding and sanitized diagnostics. Never re-execute a consumed run ID or an unknown-outcome external model call merely to obtain a result. If recovery cannot prove the state, stop with `NEEDS_DEVELOPER_RECOVERY` (a proposed future reason, not a currently implemented enum).
- **SO6 — Validation as an independent oracle.** Tests MUST derive from these requirements or a reproduced defect and include positive, negative, boundary and regression observations. Worker tests are not independent acceptance oracles. A valid failing test MUST NOT be weakened, skipped, rewritten or deleted to obtain PASS. Required canonical gates and source-bound independent review remain mandatory at existing boundaries.
- **SO7 — One stage now; bounded batch later.** `maxStagesPerInvocation=1` MUST be enforced in the first rollout, independent of the number of known stages. Later batch sizes 2–5 require a separately approved durable budget/lease/recovery protocol, single writer per repository and explicit bounded batch authorization. A configured ceiling alone MUST NOT enable multi-stage execution.
- **SO8 — Lifecycle ownership.** Sub-spec archival MUST follow developer acceptance, final review, global checks and a single coherent Git lifecycle change moving both spec and plan to archive and updating current-state owners and `STATE.json`. An archive operation MUST NOT be triggered by a worker's completed-task indication.

## Proposed repository orchestration configuration (schema v1)

A6.2 introduces a *read-only mapping and rollout ceiling*, distinct from `pipeline-policy.json` (provider/security policy) and `STATE.json` (selected checkpoint). Developer reviews and commits this file with the spec baseline. It cannot weaken either contract:

```json
{
  "schemaVersion": 1,
  "executionMode": "ONE_STAGE_MANUAL",
  "maxStagesPerInvocation": 1,
  "stagePackets": [
    {
      "specPath": "docs/specs/active/subspecs/development-agent-pipeline-stateful-orchestration.md",
      "stageId": "A6.2",
      "packetPath": "tools/agent-pipeline/examples/a62-state-resolver-stage-packet.json"
    }
  ]
}
```

The committed file at `tools/agent-pipeline/orchestrator-config.json` MUST match this version-1 shape strictly: exact keys, unique (`specPath`, `stageId`) pairs, safe bounded paths, `executionMode=ONE_STAGE_MANUAL`, ceiling exactly `1`; unknown fields, duplicated keys/entries, malformed JSON, symlinks and absent target packets are errors. Future config schema versions are separate reviewed stages. Config is an immutable execution input, not a record of stage progress and not a store for credentials. New stages require a developer checkpoint extending this mapping, not a worker-authored edit.

## Stages and acceptance contract

### A6.2 — read-only STATE-driven stage resolution (LOW / BOUNDED)

**Preconditions:** developer-committed active spec+plan+packet+config; valid `SINGLE` `STATE.json` selecting this sub-spec and `A6.2` with `phase=READY_TO_DELEGATE`; clean checkout on named `feature/*`; approved source baseline. Switching away from a previous selected workstream is an explicit **developer checkpoint between runs**; this spec patch MUST NOT silently change the repository's current A5.4a state.

**Implementation boundary:** only the existing Python CLI and focused Python tests may be modified by the worker. The committed configuration, packet, STATE, validator, schemas, spec and plan are read-only protected context. The first stage is deliberately not a state writer or a launcher for paid models.

- **SO9 — Deterministic resolver command.** Add `resolve-state` to `tools/agent-pipeline/agent_pipeline.py` with an optional explicit `--config` pointing to the reviewed repository configuration (default `tools/agent-pipeline/orchestrator-config.json`). It MUST call/reuse the real `tools/specs/validate_state.py` contract, not reimplement a weaker validator; require `READY_TO_DELEGATE`, `runId=null`, `developerDecision=UNRECORDED` and exact matching packet identity validated by existing `validate_stage_packet`. The output MUST be a stable JSON object with at least: schemaVersion, status (`READY` only on full validation), mode (`ONE_STAGE_MANUAL`), selectedWorkstreamId, specPath, stageId, packetPath, implementationPlanPath, sourceHead, configSha256, packetSha256, `executionSupported:false`, `stateMutationSupported:false`, `requiresDeveloperApproval:true`. It MUST not print raw prompt, credentials or absolute private paths.
- **SO10 — Read-only and fail-closed.** `resolve-state` MUST NOT write STATE/config/packet, create runs/worktrees/branches, invoke Aider/reviewer/checks, commit, push, or call providers. Valid request leaves Git, evidence directories and input bytes unchanged. Invalid or missing selection/config/packet/spec, non-feature branch, dirty checkout, unsafe path, conflicting stage, other phase, old runId, or multiple matching mapping rows returns nonzero and creates no side effects. No `--execute` flag is supported.
- **SO11 — Existing worker workflow unchanged.** For the A6.2 implementation itself, the developer uses the pre-existing `init-stage` and `drive-stage --execute --developer-approved` on its approved A6.2 packet; `drive-stage` consumes one run ID once, runs existing bounded worker/check/reviewer cycle, then stops at `AWAIT_DEVELOPER_PROMOTION` or escalation. Neither command automatically changes STATE, authorizes promotion or archives this sub-spec. The new resolver is tested but need not itself be invoked to bootstrap its own implementation.

**A6.2 acceptance tests:** state/config/packet positive and negative fixtures; duplicate keys/entries; exact match; `RUNNING`/`ACCEPTED`/unknown phase; stale or dirty repo and non-feature branch; symlink/path traversal; `maxStagesPerInvocation` 0/2; missing companion; no writes/provider calls across success and failures; existing `init-stage` / `drive-stage` regression. The developer independently checks output and evidence against SO1–SO11 after worker implementation. See the companion plan for executable scenarios SR-01…SR-14 and commands.

### A6.3 — checkpoint writer and deterministic reconciliation (separate HIGH-risk approval)

**Not authorized for autonomous worker implementation now.** Specify a versioned transition protocol linking expected old STATE hash, source base HEAD, verified stage promotion intent/receipt, exact promoted commit and next selected stage. Maintain a private append-only/bounded journal with deterministic recovery after crash points (before/after CAS commit, before/after receipt, before/after state-write and Git checkpoint). Updates may occur **only between source-bound runs** at explicit developer checkpoints. Atomic filesystem replacement does not atomically commit Git: recovery MUST check Git ancestry/ref, receipt and STATE hash and stop on ambiguity, never rerun worker. A6.3 may expand `STATE.json` schema only through reviewed version migration and validator changes. No implicit approval from `--developer-approved` or model output. Required independent fault-injection matrix CP-01…CP-12 precedes any authorization to execute this stage.

### A6.4 — configurable bounded sequential batch (separate execution approval)

**Not implemented and not authorized in this spec patch.** Extend config and controller with durable `batchId`, a previously approved ordered roster and exact Git/source/packet bindings per stage, serial execution, enforced ceilings for stages/provider calls/attempts/known cost, source-bound reviews and explicitly authorized guarded promotions. Start with one stage, validate two-stage pilot, then allow configurable 2–5 only after review. Cost or token usage not reported by provider is `UNKNOWN` and MUST NOT be treated as zero or as proof of unexceeded monetary budget. Interrupted/unknown model call stops; no automatic replay, rebase, push, merge or architectural reassignment. Required independent multi-stage/recovery cases BB-01…BB-12.

### A6.5 — terminal reports and bounded notifications (separate stage)

Record one sanitized, durable terminal outcome before attempting delivery; include batch/stage IDs, final state, exact Git identities, checks, reviewer verdict/findings, attempts, actual or unknown usage, failure category, next human action and private evidence location. Use a durable outbox with stable event IDs and bounded delivery retries. Delivery outage MUST NOT lose terminal report or turn a blocked batch into success. Raw prompts, diff, secrets and worker transcripts MUST NOT be sent over external notification transports. Required cases NT-01…NT-08.

### A6.6 — accepted sub-spec archival and next-workstream selection (separate HIGH-risk approval)

After developer-initiated `MAIN_MODEL` final review, required global checks, and **explicit developer acceptance**, update stable owner docs and atomically stage *both* spec and plan moves plus a validated STATE transition in one developer-controlled Git lifecycle commit. Preserve evidence and exact stage roster; detect split pairs, changed Git refs and conflicting active/archive paths. Completion is NOT acceptance. Required cases AR-01…AR-08.

## Stage sequencing and stop rules

1. **A6.2** is the only current `LOW/BOUNDED` worker candidate. No other stage may be initialized from the present packet/config.
2. After A6.2 evidence, separate `MAIN_MODEL` review and developer authorization are required before its promotion; the developer verifies/updates STATE in a later clean checkpoint. Its `READY` resolver output is a selection report, not evidence of completed work.
3. The developer and `MAIN_MODEL` MUST resolve HIGH-risk design/authority decisions for A6.3/A6.4/A6.6 in a new reviewed source baseline before authorizing worker packets; future scope and packet editable paths are deliberately **UNRESOLVED**.
4. Never infer success from `[x]`, reviewer PASS alone, prior test results, provider output, or stale `STATE.json`.

## Final sub-spec acceptance

The sub-spec is complete only when SO1–SO11 and later approved A6.3–A6.6 contracts are verified against their accepted scope, all required canonical and independent regression gates pass on final source, source-bound stage commits and terminal evidence are reconciled, `MAIN_MODEL` performs final review, and the developer explicitly accepts. Archive the spec and same-directory plan together, never as independent lifecycle items.

