---
type: Specification
title: Development agent pipeline
description: Active contributor-tooling specification for bounded coding-worker execution, fail-closed verification evidence, independent review, escalation, and externally authorized acceptance lifecycle automation.
document_role: subspec
spec_status: active
parent: ../spec-signal-harvester-platform.md
---
# Development agent pipeline

## Status

Active contributor-tooling specification.

Stage A1, the repository-local pipeline foundation under `tools/agent-pipeline/`, is accepted after canonical developer verification. P1 is now exercising that foundation on the real Stage 6E6 runtime persisted-alert verification slice. The implementation is verification-pending; P1 remains open until the developer-side run captures canonical check/review evidence and the workflow friction is reviewed.

This specification does not replace the current product implementation focus in `backend-observability-intelligence.md`. Product semantics remain owned by their product specification, implementation, contracts, tests, and current-state documentation.

## Goal

Reduce routine manual coordination and human-factor omissions in AI-assisted development while increasing reproducibility and reviewability.

The target pipeline should make required steps explicit and fail closed when evidence is missing. Coding workers may implement bounded tasks and perform routine verification, but repository rules and externally authorized acceptance remain authoritative.

## Current foundation

Accepted Stage A1 provides:

- bounded worker task packets;
- repository/spec preflight and clean-base checks;
- immutable task/policy digests;
- machine-readable run manifests and command evidence;
- policy-defined canonical verification;
- structured independent-review ingestion;
- blocking and escalation rules;
- conservative changed-path risk hints;
- a mandatory main-model checkpoint before acceptance.

Current behavior and commands are owned by [`../../../../tools/agent-pipeline/README.md`](../../../../tools/agent-pipeline/README.md). This specification owns intended multi-stage evolution and acceptance criteria, not the current command reference.

## Requirements

### AP1 — repository authority

The pipeline must treat the current repository as source of truth. Worker prompts, prior chat context, generated handoff files, and model output must not override `AGENTS.md`, active specifications, contracts, tests, or owning documentation.

### AP2 — evidence before progression

Required transitions must depend on machine-readable evidence rather than a worker assertion that a step was completed. Missing, failed, stale, or internally inconsistent required evidence must block progression.

### AP3 — separate execution from acceptance

A coding worker must not accept its own implementation, weaken a required check, or archive an active specification on its own authority. Acceptance and archival must require the configured external checkpoint.

### AP4 — isolated worker execution

When direct worker execution is introduced, each run must use an explicit base revision and isolated Git branch/worktree so unrelated local state cannot silently enter the worker result.

### AP5 — independent review and bounded repair

Implementation and independent review should use separate model contexts. Routine deterministic failures and review findings may enter bounded repair/re-review loops, but architecture, security, contract, persistence, transaction, concurrency, migration, non-determinism, and specification ambiguity must remain escalation boundaries according to repository policy.

### AP6 — repository-owned quality checks

Security/static-analysis tools introduced into the pipeline must have explicit repository-owned configuration and evidence. A worker must not silently install, disable, weaken, or reinterpret such checks to obtain a passing result.

### AP7 — lifecycle consistency

Later lifecycle automation must keep active-spec location, lifecycle status, umbrella/index navigation, implementation-task state, CHANGELOG, and owning current-state documentation consistent. A partially completed archival or contradictory lifecycle state must fail closed.

### AP8 — bounded autonomy

Risk-based batching may reduce main-model checkpoints only after repeated pilot evidence supports it. High-risk changes and configured escalation findings must always force the required main-model checkpoint regardless of nominal batch size.

## Implementation stages

- [x] **A1 — foundation:** bounded task/evidence state, required canonical check, structured independent review, conservative escalation, and mandatory main-model checkpoint.
- [ ] **P1 — real-stage pilot (verification-pending):** Stage 6E6 is the first real bounded implementation slice. Capture A1 task/check/review evidence in the developer Git worktree and record friction, missing evidence, manual steps, and incorrect or redundant assumptions before expanding automation.
- [ ] **A2 — worker execution:** add isolated Git worktree/branch preparation, direct Aider + DeepSeek invocation through an explicit adapter, and repository-owned Gitleaks execution/evidence.
- [ ] **A3 — repair and re-review:** add bounded deterministic repair loops plus fresh-context independent re-review, with explicit maximum rounds and escalation instead of unbounded self-repair.
- [ ] **A4 — structured evidence and handoff:** ingest relevant scanner/static-analysis results structurally and produce a compact main-model handoff bundle containing base revision, task/spec identity, diff/evidence summaries, review findings, and unresolved questions.
- [ ] **A5 — acceptance/lifecycle automation:** consume an external acceptance decision and automate only the mechanical documentation/specification synchronization and archival steps that the repository lifecycle permits.
- [ ] **A6 — risk-based autonomy:** allow evidence-backed batching of multiple low-risk slices between main-model checkpoints while preserving immediate escalation for high-risk paths/findings.
- [ ] **A7 — pipeline hardening and v1 acceptance:** verify interruption/recovery, stale-base detection, corrupted/missing evidence, worker/reviewer failure, contradictory spec state, and other adversarial cases before declaring the development-agent pipeline v1 accepted.

## Pilot evidence

P1 should answer at least:

- which steps still require manual coordination;
- which required evidence is difficult to collect or interpret;
- whether a worker can follow the bounded task without broad context drift;
- whether independent review catches materially different issues from worker self-checks;
- whether the main-model handoff can be made smaller without losing decision-critical context;
- whether any pipeline rule encourages unnecessary work or obscures a repository-owned decision.

Pilot findings are development-process evidence. They must not be converted into product requirements or acceptance claims without the owning product specification.

### P1 interim observations

The Stage 6E6 patch preparation already exposes two expected A1 boundaries that P1 must verify in the developer environment:

- FULL source archives intentionally exclude `.git`, so an external archive-based patch producer cannot truthfully initialize an A1 run against the developer repository base revision. Authoritative A1 run evidence must be created in the real Git worktree until A2 owns isolated worktree preparation.
- A1 records task/check/review evidence but does not invoke the worker or reviewer. Task delivery, fresh-context review invocation, and the final live Kubernetes command therefore remain manual pilot steps and are candidates for later automation only after P1 evidence is reviewed.

These observations do not count as P1 acceptance. P1 remains open until the Stage 6E6 developer-side run provides the configured repository evidence and the resulting friction is reviewed.

## Non-goals

This specification does not authorize:

- a coding model to become architecture or product authority;
- automatic weakening of tests, scanners, contracts, or acceptance criteria;
- automatic dependency installation or upgrade;
- unrestricted shell, network, credential, or deployment access for a worker;
- acceptance based only on model self-review;
- automatic merging to the main branch;
- automatic product-spec archival before the configured external acceptance decision.

## Validation

Each implemented pipeline stage must use the smallest deterministic tests that protect its orchestration/evidence semantics and must pass the canonical repository gate when repository policy requires it.

Before A7 acceptance, validation must also include deliberate failure scenarios covering at least:

- dirty or stale base state;
- task/policy mutation after run initialization;
- failed or missing required checks;
- blocking or escalation review findings;
- worker/reviewer interruption or failure;
- corrupted or contradictory evidence;
- inconsistent specification lifecycle state.

A successful model response is never validation evidence by itself.
