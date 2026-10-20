
---
type: Specification Guide
title: Change specifications
description: SignalHarvester specification hierarchy, lifecycle, feature references, and minimal metadata conventions.
---
# Change specifications

## Purpose

`docs/specs/` contains significant planned or in-progress backend changes.

A specification defines intended behavior and acceptance criteria. It is not the source of truth for behavior that is already accepted and implemented.

Current implementation truth belongs in:

- [`../ARCHITECTURE.md`](../ARCHITECTURE.md);
- [`../IMPLEMENTATION.md`](../IMPLEMENTATION.md);
- [`../CONFIGURATION.md`](../CONFIGURATION.md);
- [`../USAGE.md`](../USAGE.md);
- owning module and contract documentation.

Stable capability names belong in [`../FEATURES.md`](../FEATURES.md).

Frontend implementation specifications belong in the separate `signalharvester-web/docs/specs/` tree.

## Feature, requirement, and specification IDs

Keep these concepts separate:

- **Feature ID** — stable capability vocabulary from [`../FEATURES.md`](../FEATURES.md), for example `ANALYSIS.OUTBOX`.
- **Requirement ID** — normative requirement inside one specification, for example `R22` or `A5`.
- **Specification name** — one implementation/change slice, for example `backend-authentication-authorization`.

A feature may be refined by several specifications. A completed specification is archived, but its feature IDs remain stable.

Do not invent feature IDs in tests, classes, or specifications. Add a genuinely new durable capability to `docs/FEATURES.md` first.

## Processing-state contract

`docs/specs/STATE.json` is the **only Git-tracked processing checkpoint** for selected specification work, not a live process monitor or an acceptance receipt. Validate it with `python3 tools/specs/validate_state.py`; schema is `docs/specs/state.schema.json`. It is distinct from spec frontmatter `spec_status` (document lifecycle), run-local worker evidence and Git authority. `STATE.json` has no authority to execute a worker, accept a stage, promote Git or archive a spec. Only a developer-controlled checkpoint transition may change it. Update/commit it **between** source-bound runs, never during an initialized/running/pending-promotion run: modifying the checkout makes the immutable base dirty or changes its HEAD. In-progress runtime state belongs exclusively to private `runId` evidence. v1 deliberately forbids `RUNNING` and `ACCEPTED`; final acceptance is recorded through the existing explicit developer review and spec archival lifecycle, not a self-asserted JSON field. Missing/invalid/stale state MUST be reported, not inferred from README.

`workstreams` is a list keyed by unique IDs and spec paths so a later implementation can track concurrent sub-specs without changing the shape. Current `mode: SINGLE` allows **at most one selected workstream**, and no concurrent worker/executor runs are authorized. `selectedWorkstreamId` is an information pointer, not a lock. A future MULTI policy needs separate requirements, lock ownership, CAS and tests before enablement. README and umbrella `current_focus` are navigation only and MUST link to `STATE.json`, not replicate mutable processing progress.

## Active tree

Keep the active tree shallow:

```text
active/
    spec-<umbrella>.md
    subspecs/
        <bounded-focus>.md
```

Rules:

- processing status is stored only in `STATE.json`, not in this index;
- the umbrella defines the active product/system target;
- one sub-spec may be the current implementation focus;
- other sub-specs may remain active only while they define unresolved supporting constraints;
- completing one sub-spec does not complete the umbrella;
- do not create deeper trees without a concrete need.

For active product/change slices, navigate to [`active/`](active/); the machine-readable selected workflow is [`STATE.json`](STATE.json). Product Stage 6E6 remains paused in [`backend-observability-intelligence.md`](active/subspecs/backend-observability-intelligence.md), separately from contributor automation in [`development-agent-pipeline.md`](active/subspecs/development-agent-pipeline.md). Historical acceptance and implementation evidence stay in their owning specs, not in this index.

Accepted Kubernetes/observability deployment, resilience acceptance, authentication/RBAC, application observability, external-source access security, and scheduler pre-run lease recovery are archived.

## When to create a sub-spec

Create a sub-spec when a change materially affects one or more of these areas:

- module ownership or dependency direction;
- persistence or transaction semantics;
- public HTTP/SSE or Kafka contracts;
- reliability and failure behavior;
- authentication or authorization;
- deployment or observability;
- work that spans multiple implementation sessions and needs stable acceptance criteria.

Do not create a sub-spec for:

- small bugs;
- local refactors;
- narrow documentation cleanup;
- routine dependency maintenance.

## Delegation plans for staged sub-specs

A sub-spec with known module ownership **may** include a `Delegation plan` body section. Use it to
describe shared implementation scope once for related stages, with stage-specific exceptions only when
needed. Do not add new frontmatter fields or duplicate authoritative module contracts.

When using a delegation plan, identify:

- existing feature IDs from `docs/FEATURES.md` and stage-specific requirement IDs;
- owning modules, permitted editable paths, and applicable root/local `AGENTS.md`;
- read-only owning documentation, module contracts/tests, and other modules' public APIs or published contracts needed as context;
- focused and canonical checks, applicable scanner/linter/static-analysis commands, and acceptance evidence;
- stage order, known risks, and any stage that requires architecture discovery or developer approval.

Classify stages as **bounded/known ownership** or **discovery/architecture required**. Risk is separate:
even a bounded stage may need human authorization when it changes contracts, persistence, transactions,
concurrency, security, or deployment. Unknown ownership or missing feature vocabulary requires human-led
design before delegation; define any genuinely new feature ID in `docs/FEATURES.md` through the normal
specification workflow.

The active [`development-agent-pipeline`](active/subspecs/development-agent-pipeline.md) specification
owns the intended task-packet, context-resolution, retry, and acceptance controls. Sub-spec authors
describe requirements and scope; they do not embed executable Aider startup scripts or treat the
delegation plan as a security boundary. The current pipeline cannot yet execute a whole sub-spec
autonomously.

## Sub-specification module impact and implementation plans

Every newly authored staged sub-specification must identify its **known implementation module impact**
in the normative sub-spec body. Use an `Implementation scope` section (or the existing `Delegation plan`
when applicable) to distinguish owning modules, cross-module public contracts/APIs needed for context,
known affected Feature IDs, and areas that remain undecided. Name stage-specific exceptions only when
the shared scope does not apply. Do not invent ownership: unresolved modules, contracts, or feature
vocabulary must be marked for developer/`MAIN_MODEL` discovery and must not be authorized for autonomous
worker edits. Historical sub-specifications do not need retroactive companion files.

For each newly authored **staged** sub-specification, `MAIN_MODEL` must also prepare a same-directory advisory companion named `<subspec-stem>-implementation-plan.md`, for example:

```text
docs/specs/active/subspecs/example-change.md
docs/specs/active/subspecs/example-change-implementation-plan.md
```

The companion is **not a separate sub-specification**, must not have `document_role: subspec`, and must
not acquire its own acceptance status. It follows its owning sub-specification through the
active/archive lifecycle. Use normal minimal document metadata if frontmatter is present; link to the
relative sub-spec path in the document body. Include, where knowable and useful:

- the owning sub-spec and existing stage/requirement IDs (do not invent new IDs);
- likely architectural approach, existing patterns, important invariants, and relevant alternatives;
- applicable root/local `AGENTS.md`, owning READMEs, module contracts, active specs, tests, and public interfaces of related modules to read;
- per-stage architecture and concrete file-by-file implementation steps, identifying current APIs and integration boundaries;
- likely existing files to modify and candidate files to add, marked **expected**, **candidate**, or **to be discovered**;
- reference method signatures, algorithms, pseudocode, bounded code/SQL/JSON examples, and test fixtures where they reduce ambiguity;
- data contracts, ordering, failure semantics, compatibility, security, concurrency, transaction and recovery guarantees relevant to the slice;
- positive, negative, boundary, regression, and adversarial test cases with **stable scenario IDs**, fixtures, expected outcomes, and exact verification commands;
- dependencies between stages, alternatives, trade-offs, unresolved decisions, and risks that block autonomous delegation.

The sub-specification remains **normative** for behavior, authorized module boundaries, constraints, and
acceptance criteria. The companion is **advisory**: it must not override repository rules, current
contracts, active requirements, developer approvals, or the validated stage task packet. `WORKER_MODEL`
may propose a better implementation within approved boundaries, but must record material deviations and
their effect on files, contracts, tests, or risk. Architectural or scope changes require developer-led
review and updates to the owning sub-specification before further delegated work.

### Depth and presentation of implementation blueprints

The advisory plan should be **as detailed as is useful to implement and verify the stage**, not limited
to a generic checklist. Give special attention to transaction ownership, event ordering, concurrency,
cancellation, security and evidence integrity. Prefer small runnable or illustrative examples over long
prose when that makes an invariant testable.

- **Label every example** as illustrative/reference code unless it is copied from and linked to an existing authoritative implementation. Do not imply that examples are already integrated or tested.
- **Distinguish** normative requirements (in the owning active spec) from one recommended approach (in the plan). Reference AP/feature IDs; do not invent new ones in the plan.
- **Avoid stale duplication:** refer to current contracts, owning documentation and implementation by path/symbol; quote only the fragment needed to explain a non-obvious algorithm. Do not maintain a second full copy of production code.
- **Make scenarios executable:** specify setup, mutation/action, expected observation and failure mode. Include independent negative/adversarial cases; a model-authored test suite is not its own independent oracle.
- **Optimize for LLM interpretation:** write precise preconditions, postconditions, ownership, ordered operations and rejection conditions; omit untestable generalities. Use normative `must` / `must not` / `may` deliberately.
- **Keep unambiguous structure:** separate status, sequences,
  invariants, errors, implementation steps and test matrices with headings, lists, tables or short code
  blocks. Avoid very long unbroken status paragraphs.
- **Keep scope honest:** mark candidate files, unverified assumptions, unresolved decisions and deferred stages clearly. An extensive plan is still not a task authorization, changed-path permission or acceptance record.
- **Do not retroactively expand every legacy plan** without a relevant active implementation stage. Expand the paired companion when new work is planned; archive it with the parent after developer acceptance.

The plan may be long when it contains distinct actionable engineering evidence. Repetitive narrative, copied current-state documentation and repeated specification requirements are not useful depth.

`MAIN_MODEL` and the developer should update the companion when assumptions, module impact, or the
architectural approach materially change. Do not rewrite it after every private refactor. The pipeline's
planned stage packet should bind the versions/digests of both the normative spec and its companion when
present, and provide the companion as read-only context. A missing required or materially stale
companion blocks new autonomous stage delegation; it does not alter acceptance requirements or authorize
a broader scope. Implementation plans are not listed as independent active sub-specifications in this
index.

## Lifecycle

When a delegated stage ends `BLOCKED`/`ESCALATED`, the developer may request a
`MAIN_MODEL` recovery review with the latest repository snapshot and sanitized
attempt evidence. The corrected patch must be reverified on its new source
state before the stage resumes; the failed run remains historical evidence.
The repository-owned recovery steps are in
[`tools/agent-pipeline/README.md`](../../tools/agent-pipeline/README.md).

1. Keep the umbrella under `docs/specs/active/spec-*.md`.
2. Put one unresolved bounded implementation slice under `docs/specs/active/subspecs/`.
3. Record its parent and lifecycle state in frontmatter.
4. Update and commit validated `docs/specs/STATE.json` only at developer-controlled Git checkpoints **between** stages; never mutate it during source-bound execution or infer acceptance; any umbrella `current_focus` is only a link/pointer and must not be used to infer approval.
5. Reference stable feature IDs from `docs/FEATURES.md` when they improve navigation.
6. Give important normative requirements stable local IDs when tests or reviews need them.
7. Implement and validate the slice; retain source-bound stage, check, and review evidence.
8. The **developer initiates final verification** by asking `MAIN_MODEL` in ChatGPT to review the finished sub-spec against current repository sources, its companion plan, stage diffs/commits, tests, and independent reviewer findings.
9. `MAIN_MODEL` reports remaining defects, risks, and an acceptance recommendation; **only the developer** may explicitly accept the completed sub-spec.
10. After developer acceptance, move stable behavior into current-state documentation and synchronize umbrella/index references.
11. Move the completed sub-spec **and its implementation-plan companion** together to `docs/specs/archive/subspecs/` in one lifecycle change; update relative links as needed.
12. Archive the umbrella only when its own acceptance target is complete.

A specification and its companion must each exist in exactly one lifecycle location. Archival is a move,
not a copy. The pair must not be split between `active/` and `archive/`. For legacy sub-specifications
created before the companion convention, a missing plan does not block historical acceptance or
archival.

An implemented but unverified slice may remain active with `spec_status: verification-pending`.

After acceptance:

- current-state documentation owns the behavior;
- the archived spec is historical context only.

## Document metadata

Managed documentation uses minimal YAML frontmatter:

- `type`;
- `title`;
- `description`.

Specifications may additionally use:

- `document_role` — `umbrella` or `subspec`;
- `spec_status` — for example `active`, `verification-pending`, or `completed` in an archived spec;
- `parent` — umbrella path for a sub-spec;
- `current_focus` — optional informational pointer; `docs/specs/STATE.json` owns processing state.

Do not add metadata that only repeats the body or Git history.

## Writing structure

Prefer a small predictable structure. Use only sections that add value.

```text
# <Change name>

## Status
## Feature scope
## Goal
## Current state
## Requirements
## Processing sequence          # when order matters
## Failure semantics           # when failures need separate rules
## Scenarios
## Non-goals
## Design constraints
## Compatibility / migration
## Validation
## Implementation tasks
```

Writing rules:

- keep normative requirements precise;
- prefer short paragraphs with one main assertion;
- use lists for independent rules or steps;
- keep processing order separate from invariants and failure semantics;
- do not restate accepted current-state detail when a current-state document already owns it.

## Active specifications

Umbrella:

- [`active/spec-signal-harvester-platform.md`](active/spec-signal-harvester-platform.md) — initial observable collection/analysis platform target.

Current implementation focus:

-
  [`active/subspecs/backend-observability-intelligence.md`](active/subspecs/backend-observability-intelligence.md)
  — scenario-based operational-intelligence evaluation; detector/alert evaluation, bounded runtime trial
  capture/export, repeated-evidence calibration, and the dedicated repeated live Health/alert
  calibration evidence review are accepted. Stage 6E6 persisted runtime-alert lifecycle verification is
  implemented, verification-pending, and deliberately paused after clean baseline/transient evidence
  exposed a five-minute statistical recovery mismatch plus cleanup after the backend control channel had
  exited.

Verification-pending carryover:

-
  [`active/subspecs/backend-capacity-telemetry-expansion.md`](active/subspecs/backend-capacity-telemetry-expansion.md)
  — Analysis outbox backlog/batch/publication/database telemetry selected from the measured local
  replica comparison; canonical acceptance still pending.

-
  [`active/subspecs/backend-capacity-observability-baseline.md`](active/subspecs/backend-capacity-observability-baseline.md)
  — bounded deterministic Kubernetes pipeline capacity measurement; live one-replica and
  one-versus-three runs completed, canonical acceptance still pending.
-
  [`active/subspecs/backend-analysis-all-relevant-default.md`](active/subspecs/backend-analysis-all-relevant-default.md)
  — explicit all-relevant Analysis settings for profiles without keyword filtering; implementation
  complete, canonical acceptance still pending.

Active supporting tracks:

- [`active/subspecs/backend-project-structure.md`](active/subspecs/backend-project-structure.md) — `PLATFORM.MODULAR_MONOLITH` and related boundary/testing guardrails;
- [`active/subspecs/backend-event-contracts.md`](active/subspecs/backend-event-contracts.md) — `CONTRACTS.KAFKA_PROTOBUF` and event compatibility rules.

Active contributor-tooling track:

- [`active/subspecs/development-agent-pipeline.md`](active/subspecs/development-agent-pipeline.md) —
  bounded coding-worker automation, verification/review evidence, escalation, and externally authorized
  acceptance/lifecycle automation. A2.1–A2.3 worker/verification/reviewer integration is implemented and
  verification-pending; the first real edit-format pilot did not produce a diff. This track has no
  product feature ID and does not replace the backend implementation focus.

## Recently completed sub-specifications

Accepted on 2026-09-21:

-
  [`archive/subspecs/backend-analysis-outbox-failure-lease-fencing.md`](archive/subspecs/backend-analysis-outbox-failure-lease-fencing.md)
  — Analysis outbox publication-failure retry metadata now requires a still-live exact-token lease;
  accepted after the developer confirmed all relevant tests and validations passed. The remaining
  durable-ownership/crash-window review found no additional material correctness defect.
-
  [`archive/subspecs/backend-analysis-outbox-expired-lease-fencing.md`](archive/subspecs/backend-analysis-outbox-expired-lease-fencing.md)
  — Analysis outbox pre-publication renewal now requires a still-live exact-token lease; accepted after
  the developer confirmed all relevant tests and validations passed.
-
  [`archive/subspecs/backend-source-fetch-worker-interruption-propagation.md`](archive/subspecs/backend-source-fetch-worker-interruption-propagation.md)
  — Collection source-fetch worker interruption is propagated to coordinator-thread cancellation and
  scheduler recovery; accepted after the developer confirmed all relevant tests and validations passed.
-
  [`archive/subspecs/backend-collection-run-interruption-recovery.md`](archive/subspecs/backend-collection-run-interruption-recovery.md)
  — Collection publication interruption aborts run-level work and interrupted scheduled runs leave
  next-due recovery to lease expiry; accepted after the developer confirmed all relevant tests and
  validations passed.
-
  [`archive/subspecs/backend-shared-polling-lifecycle-refactoring.md`](archive/subspecs/backend-shared-polling-lifecycle-refactoring.md)
  — Results and Event Observation share one framework-neutral demand/poll/cancellation lifecycle
  primitive in `common`; accepted after the developer confirmed all relevant tests and validations
  passed.
-
  [`archive/subspecs/backend-sse-inflight-poll-cancellation.md`](archive/subspecs/backend-sse-inflight-poll-cancellation.md)
  — active Results/Event Observation blocking SSE polls are interrupted on client cancellation; accepted
  after the developer confirmed all relevant tests and validations passed.
-
  [`archive/subspecs/backend-analysis-outbox-interruption-fencing.md`](archive/subspecs/backend-analysis-outbox-interruption-fencing.md)
  — Analysis outbox lifecycle interruption stops the claimed batch before ordinary publication-failure
  classification; accepted after the developer confirmed all relevant tests and validations passed.

Accepted on 2026-09-20:

-
  [`archive/subspecs/backend-kafka-listener-failed-poll-rewind.md`](archive/subspecs/backend-kafka-listener-failed-poll-rewind.md)
  — failed-poll rewind for Analysis, Results, and Event Observation; accepted after the developer
  confirmed all relevant tests and validations passed.
-
  `backend-kafka-listener-interruption-fencing` (historical change; no tracked archive document)
  — listener-thread interruption fencing for Analysis, Results, and Event Observation; accepted after
  the developer confirmed all relevant tests and validations passed.
-
  [`archive/subspecs/backend-scheduler-expired-lease-fencing.md`](archive/subspecs/backend-scheduler-expired-lease-fencing.md)
  — `COLLECTION.SCHEDULING` expiry fencing that rejects stale renewal/completion after `lease_until` and
  leaves overdue work reclaimable; accepted after the developer confirmed all relevant tests and
  validations passed.
-
  [`archive/subspecs/backend-module-contract-discoverability.md`](archive/subspecs/backend-module-contract-discoverability.md)
  — `PLATFORM.MODULAR_MONOLITH`, `CONTRACTS.HTTP`, and `CONTRACTS.KAFKA_PROTOBUF` discoverability
  hardening; accepted after the developer confirmed all relevant repository tests and validations
  passed.
-
  [`archive/subspecs/backend-kafka-offset-commit-failure-separation.md`](archive/subspecs/backend-kafka-offset-commit-failure-separation.md)
  — `RELIABILITY.KAFKA_RETRY`, `RELIABILITY.DEAD_LETTER`, and `RELIABILITY.IDEMPOTENCY`; accepted after
  the corrected Micronaut `SYNC_PER_RECORD` implementation completed focused listener verification,
  `HttpPipelineSmokeIntegrationTest`, and the canonical repository gate.
-
  [`archive/subspecs/backend-analysis-outbox-lease-renewal.md`](archive/subspecs/backend-analysis-outbox-lease-renewal.md)
  — `ANALYSIS.OUTBOX` exact-token pre-publication lease renewal so sequential batch queueing cannot
  expire later-row ownership before Kafka send; accepted after focused Analysis verification and the
  canonical repository gate passed.
-
  [`archive/subspecs/backend-scheduler-pre-run-lease-recovery.md`](archive/subspecs/backend-scheduler-pre-run-lease-recovery.md)
  — `COLLECTION.SCHEDULING` pre-run recovery that releases exact-token leases after local dispatch/setup
  failure without advancing due time; accepted after the developer confirmed focused Collection
  verification and the canonical repository gate passed.

Accepted on 2026-09-18:

-
  [`archive/subspecs/backend-jdbi-persistence-refactoring.md`](archive/subspecs/backend-jdbi-persistence-refactoring.md)
  — repository-wide migration from direct JDBC statement plumbing to Jdbi with application-owned
  transactions, explicit SQL resources, bounded Results templating, and verified Security lock-session
  parity.
-
  [`archive/subspecs/backend-controlled-dead-letter-recovery.md`](archive/subspecs/backend-controlled-dead-letter-recovery.md)
  — ADMIN-only owner-specific dead-letter inspection/replay; accepted after the developer confirmed the
  canonical repository gate passed.
-
  [`archive/subspecs/backend-profile-owned-analysis-settings.md`](archive/subspecs/backend-profile-owned-analysis-settings.md)
  — typed Monitoring Profile Analysis settings and immutable raw-event settings snapshots; accepted
  after the canonical repository gate passed.
-
  [`archive/subspecs/backend-results-production-browsing.md`](archive/subspecs/backend-results-production-browsing.md)
  — backward-compatible keyset pagination and indexed text search for Results; accepted after the
  canonical repository gate passed.

Accepted on 2026-09-17:

-
  [`archive/subspecs/backend-kafka-consumer-horizontal-scaling.md`](archive/subspecs/backend-kafka-consumer-horizontal-scaling.md)
  — `SCALABILITY.KAFKA_CONSUMERS`; accepted live one-to-three replica consumer scaling with
  partition-bounded backlog drain;
- [`archive/subspecs/backend-system-resilience-acceptance.md`](archive/subspecs/backend-system-resilience-acceptance.md) — live restart, outage, lag, outbox, scheduler, authorization, recovery, and telemetry acceptance;
- [`archive/subspecs/backend-kubernetes-observability-deployment.md`](archive/subspecs/backend-kubernetes-observability-deployment.md) — backend-owned `DEPLOYMENT.KUBERNETES` and `OBSERVABILITY.INFRASTRUCTURE`.

Accepted security/observability/reliability slices:

- [`archive/subspecs/backend-external-source-access-security.md`](archive/subspecs/backend-external-source-access-security.md) — `SECURITY.EXTERNAL_SOURCE_ACCESS`;
- [`archive/subspecs/backend-authentication-authorization.md`](archive/subspecs/backend-authentication-authorization.md) — `SECURITY.IDENTITY_ROLES`, `SECURITY.AUTHENTICATION`, and `SECURITY.AUTHORIZATION`;
- [`archive/subspecs/backend-application-observability.md`](archive/subspecs/backend-application-observability.md) — `OBSERVABILITY.APPLICATION`;
- [`archive/subspecs/backend-db-kafka-consistency.md`](archive/subspecs/backend-db-kafka-consistency.md) — `ANALYSIS.OUTBOX`;
- [`archive/subspecs/backend-reliability-failure-handling.md`](archive/subspecs/backend-reliability-failure-handling.md) — `RELIABILITY.KAFKA_RETRY` and `RELIABILITY.DEAD_LETTER`.

Accepted diagnostic/result slices:

- [`archive/subspecs/backend-processing-flow-reconstruction.md`](archive/subspecs/backend-processing-flow-reconstruction.md) — `DIAGNOSTICS.PROCESSING_FLOW`;
- [`archive/subspecs/backend-event-observation.md`](archive/subspecs/backend-event-observation.md) — `DIAGNOSTICS.EVENT_OBSERVATION`;
- [`archive/subspecs/backend-results-sse-live-delivery.md`](archive/subspecs/backend-results-sse-live-delivery.md) — `RESULTS.LIVE`;
- [`archive/subspecs/backend-results-rest-api.md`](archive/subspecs/backend-results-rest-api.md) — `RESULTS.BROWSING`;
- [`archive/subspecs/backend-results-persistence.md`](archive/subspecs/backend-results-persistence.md) — `RESULTS.MATERIALIZATION`.

Accepted configuration/collection/analysis slices:

- [`archive/subspecs/backend-source-test-generic-extraction.md`](archive/subspecs/backend-source-test-generic-extraction.md) — `COLLECTION.SOURCE_TEST`;
- [`archive/subspecs/backend-profile-driven-scheduling.md`](archive/subspecs/backend-profile-driven-scheduling.md) — `COLLECTION.SCHEDULING`;
- [`archive/subspecs/backend-monitoring-profile-configuration.md`](archive/subspecs/backend-monitoring-profile-configuration.md) — `CONFIGURATION.MONITORING_PROFILES`;
- [`archive/subspecs/backend-rss-atom-extraction.md`](archive/subspecs/backend-rss-atom-extraction.md) — `COLLECTION.ADAPTERS`;
- [`archive/subspecs/backend-configuration-persistence-rest.md`](archive/subspecs/backend-configuration-persistence-rest.md) — `CONFIGURATION.SOURCES`;
- [`archive/subspecs/backend-operational-admin-api.md`](archive/subspecs/backend-operational-admin-api.md) — `COLLECTION.RUNS` and `DIAGNOSTICS.ANALYSIS_INSPECTION` operational APIs;
- [`archive/subspecs/backend-analysis-normalization-deduplication.md`](archive/subspecs/backend-analysis-normalization-deduplication.md) — `ANALYSIS.NORMALIZATION`, `ANALYSIS.DEDUPLICATION`, and `ANALYSIS.CLASSIFICATION`;
- [`archive/subspecs/backend-collection-kafka-transport.md`](archive/subspecs/backend-collection-kafka-transport.md) — collection-side Kafka transport and event publication;
- [`archive/subspecs/backend-collection-run-orchestration.md`](archive/subspecs/backend-collection-run-orchestration.md) — `COLLECTION.RUNS` orchestration and durable outcomes.

## Planned backend sub-specifications

There is no planned bounded backend sub-specification at this point. The systematic
lifecycle/reliability review is complete; create another backend sub-spec only when a concrete product,
operational, or correctness requirement justifies one.

Further scheduling and capacity refinements remain deferred until measurements or a product requirement justify them.

These are candidates, not active commitments.

Companion-frontend planning and implementation lifecycle belong to `signalharvester-web` and are not maintained in this backend specification index.
