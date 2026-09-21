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

## Active tree

Keep the active tree shallow:

```text
active/
    spec-<umbrella>.md
    subspecs/
        <bounded-focus>.md
```

Rules:

- the umbrella defines the active product/system target;
- one sub-spec may be the current implementation focus;
- other sub-specs may remain active only while they define unresolved supporting constraints;
- completing one sub-spec does not complete the umbrella;
- do not create deeper trees without a concrete need.

The current bounded implementation focus is [`active/subspecs/backend-shared-polling-lifecycle-refactoring.md`](active/subspecs/backend-shared-polling-lifecycle-refactoring.md). After SSE in-flight cancellation was accepted, a focused duplication review identified stable demand/scheduling/cancellation lifecycle duplicated by Results and Event Observation. The bounded framework-neutral common extraction is implemented and awaits verification.

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

## Lifecycle

1. Keep the umbrella under `docs/specs/active/spec-*.md`.
2. Put one unresolved bounded implementation slice under `docs/specs/active/subspecs/`.
3. Record its parent and lifecycle state in frontmatter.
4. Keep umbrella `current_focus` and human-readable status synchronized.
5. Reference stable feature IDs from `docs/FEATURES.md` when they improve navigation.
6. Give important normative requirements stable local IDs when tests or reviews need them.
7. Implement and validate the slice.
8. After developer acceptance, move stable behavior into current-state documentation.
9. Move the completed sub-spec to `docs/specs/archive/subspecs/`.
10. Archive the umbrella only when its own acceptance target is complete.

A specification must exist in exactly one lifecycle location. Archival is a move, not a copy.

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
- `current_focus` — only when an umbrella points to one active implementation sub-spec.

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

- [`active/subspecs/backend-shared-polling-lifecycle-refactoring.md`](active/subspecs/backend-shared-polling-lifecycle-refactoring.md) — share generic demand, delayed scheduling, and cancellable blocking-poll lifecycle without centralizing module-owned SSE semantics.

Active supporting tracks:

- [`active/subspecs/backend-project-structure.md`](active/subspecs/backend-project-structure.md) — `PLATFORM.MODULAR_MONOLITH` and related boundary/testing guardrails;
- [`active/subspecs/backend-event-contracts.md`](active/subspecs/backend-event-contracts.md) — `CONTRACTS.KAFKA_PROTOBUF` and event compatibility rules.

## Recently completed sub-specifications

Accepted on 2026-09-21:

- [`archive/subspecs/backend-sse-inflight-poll-cancellation.md`](archive/subspecs/backend-sse-inflight-poll-cancellation.md) — active Results/Event Observation blocking SSE polls are interrupted on client cancellation; accepted after the developer confirmed all relevant tests and validations passed.
- [`archive/subspecs/backend-analysis-outbox-interruption-fencing.md`](archive/subspecs/backend-analysis-outbox-interruption-fencing.md) — Analysis outbox lifecycle interruption stops the claimed batch before ordinary publication-failure classification; accepted after the developer confirmed all relevant tests and validations passed.

Accepted on 2026-09-20:

- [`archive/subspecs/backend-kafka-listener-failed-poll-rewind.md`](archive/subspecs/backend-kafka-listener-failed-poll-rewind.md) — failed-poll rewind for Analysis, Results, and Event Observation; accepted after the developer confirmed all relevant tests and validations passed.
- [`archive/subspecs/backend-kafka-listener-interruption-fencing.md`](archive/subspecs/backend-kafka-listener-interruption-fencing.md) — listener-thread interruption fencing for Analysis, Results, and Event Observation; accepted after the developer confirmed all relevant tests and validations passed.
- [`archive/subspecs/backend-scheduler-expired-lease-fencing.md`](archive/subspecs/backend-scheduler-expired-lease-fencing.md) — `COLLECTION.SCHEDULING` expiry fencing that rejects stale renewal/completion after `lease_until` and leaves overdue work reclaimable; accepted after the developer confirmed all relevant tests and validations passed.
- [`archive/subspecs/backend-module-contract-discoverability.md`](archive/subspecs/backend-module-contract-discoverability.md) — `PLATFORM.MODULAR_MONOLITH`, `CONTRACTS.HTTP`, and `CONTRACTS.KAFKA_PROTOBUF` discoverability hardening; accepted after the developer confirmed all relevant repository tests and validations passed.
- [`archive/subspecs/backend-kafka-offset-commit-failure-separation.md`](archive/subspecs/backend-kafka-offset-commit-failure-separation.md) — `RELIABILITY.KAFKA_RETRY`, `RELIABILITY.DEAD_LETTER`, and `RELIABILITY.IDEMPOTENCY`; accepted after the corrected Micronaut `SYNC_PER_RECORD` implementation completed focused listener verification, `HttpPipelineSmokeIntegrationTest`, and the canonical repository gate.
- [`archive/subspecs/backend-analysis-outbox-lease-renewal.md`](archive/subspecs/backend-analysis-outbox-lease-renewal.md) — `ANALYSIS.OUTBOX` exact-token pre-publication lease renewal so sequential batch queueing cannot expire later-row ownership before Kafka send; accepted after focused Analysis verification and the canonical repository gate passed.
- [`archive/subspecs/backend-scheduler-pre-run-lease-recovery.md`](archive/subspecs/backend-scheduler-pre-run-lease-recovery.md) — `COLLECTION.SCHEDULING` pre-run recovery that releases exact-token leases after local dispatch/setup failure without advancing due time; accepted after the developer confirmed focused Collection verification and the canonical repository gate passed.

Accepted on 2026-09-18:

- [`archive/subspecs/backend-jdbi-persistence-refactoring.md`](archive/subspecs/backend-jdbi-persistence-refactoring.md) — repository-wide migration from direct JDBC statement plumbing to Jdbi with application-owned transactions, explicit SQL resources, bounded Results templating, and verified Security lock-session parity.
- [`archive/subspecs/backend-controlled-dead-letter-recovery.md`](archive/subspecs/backend-controlled-dead-letter-recovery.md) — ADMIN-only owner-specific dead-letter inspection/replay; accepted after the developer confirmed the canonical repository gate passed.
- [`archive/subspecs/backend-profile-owned-analysis-settings.md`](archive/subspecs/backend-profile-owned-analysis-settings.md) — typed Monitoring Profile Analysis settings and immutable raw-event settings snapshots; accepted after the canonical repository gate passed.
- [`archive/subspecs/backend-results-production-browsing.md`](archive/subspecs/backend-results-production-browsing.md) — backward-compatible keyset pagination and indexed text search for Results; accepted after the canonical repository gate passed.

Accepted on 2026-09-17:

- [`archive/subspecs/backend-kafka-consumer-horizontal-scaling.md`](archive/subspecs/backend-kafka-consumer-horizontal-scaling.md) — `SCALABILITY.KAFKA_CONSUMERS`; accepted live one-to-three replica consumer scaling with partition-bounded backlog drain;
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

There is no planned bounded backend sub-specification at this point. Further lifecycle/reliability review remains analysis-only until it identifies another concrete material change.

Further scheduling refinements remain deferred until a product requirement justifies them.

These are candidates, not active commitments.

Companion-frontend planning and implementation lifecycle belong to `signalharvester-web` and are not maintained in this backend specification index.
