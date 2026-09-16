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

Stable capability names belong in [`../FEATURES.md`](../FEATURES.md). Frontend implementation specifications belong in the separate `signalharvester-web/docs/specs/` tree.

## Feature, requirement, and specification IDs

Keep these concepts separate:

- **Feature ID** — stable capability vocabulary from [`../FEATURES.md`](../FEATURES.md), for example `ANALYSIS.OUTBOX`.
- **Requirement ID** — normative requirement inside one specification, for example `R22` or `A5`.
- **Specification name** — one implementation/change slice, for example `backend-authentication-authorization`.

A feature may be refined by several specifications over time. A completed specification is archived; its feature ID remains stable.

Do not invent feature IDs inside a test, class, or specification. Add the capability to `docs/FEATURES.md` first.

## Active tree

Keep the active tree shallow:

```text
active/
    spec-<umbrella>.md
    subspecs/
        <bounded-focus>.md
```

The rules are:

- the umbrella defines the active product/system target;
- one sub-spec may be the current implementation focus;
- other sub-specs may remain active only when they define unresolved supporting constraints;
- completing one sub-spec does not complete the umbrella;
- do not create deeper trees without a concrete need.

The current implementation focus is `DEPLOYMENT.KUBERNETES` and `OBSERVABILITY.INFRASTRUCTURE` through `active/subspecs/backend-kubernetes-observability-deployment.md`. Authentication/RBAC, application observability, and external-source access security are accepted and archived.

## When to create a sub-spec

Create a sub-spec when a change materially affects one or more of these areas:

- module ownership or dependency direction;
- persistence or transaction semantics;
- public HTTP/SSE or Kafka contracts;
- reliability and failure behavior;
- authentication/authorization;
- deployment or observability;
- work that spans multiple implementation sessions and benefits from stable acceptance criteria.

Small bugs, local refactors, narrow documentation cleanup, and routine dependency maintenance do not require a sub-spec.

## Lifecycle

1. Keep the umbrella under `docs/specs/active/spec-*.md`.
2. Put an unresolved bounded implementation slice under `docs/specs/active/subspecs/`.
3. Record its parent and lifecycle state in frontmatter.
4. If the umbrella has a current implementation focus, make `current_focus` and the human-readable status agree.
5. Reference stable feature IDs from `docs/FEATURES.md` when they improve navigation.
6. Give important normative requirements stable IDs when tests or reviews need them.
7. Implement and validate the slice.
8. After developer acceptance, move stable behavior into current-state documentation.
9. Move the completed sub-spec to `docs/specs/archive/subspecs/`.
10. Archive the umbrella only when its own acceptance target is complete.

A specification must exist in exactly one lifecycle location. Archival is a move, not a copy.

An implemented but unverified slice may remain active with `spec_status: verification-pending`. Once accepted, current-state documentation owns the behavior; the archived spec remains historical context only.

## Document metadata

Managed documentation uses minimal YAML frontmatter:

- `type`;
- `title`;
- `description`.

Specification files may additionally use:

- `document_role` — `umbrella` or `subspec`;
- `spec_status` — for example `active`, `verification-pending`, or `completed` in an archived spec;
- `parent` — umbrella path for a sub-spec;
- `current_focus` — only when an umbrella currently points to one active implementation sub-spec.

Do not add metadata that only repeats the body or Git history.

## Writing structure

Prefer a small, predictable structure. Use only the sections that add value.

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

Keep processing order separate from invariants and failure semantics. Use short paragraphs and bullets rather than combining several independent requirements into one sentence.

## Active specifications

Umbrella:

- [`active/spec-signal-harvester-platform.md`](active/spec-signal-harvester-platform.md) — initial observable collection/analysis platform target.

Current implementation focus:

- [`active/subspecs/backend-kubernetes-observability-deployment.md`](active/subspecs/backend-kubernetes-observability-deployment.md) — backend-owned `DEPLOYMENT.KUBERNETES` and `OBSERVABILITY.INFRASTRUCTURE`; implementation complete, developer Kubernetes verification pending.

Active supporting tracks:

- [`active/subspecs/backend-project-structure.md`](active/subspecs/backend-project-structure.md) — `PLATFORM.MODULAR_MONOLITH` and related boundary/testing guardrails;
- [`active/subspecs/backend-event-contracts.md`](active/subspecs/backend-event-contracts.md) — `CONTRACTS.KAFKA_PROTOBUF` and event compatibility rules.

## Recently completed sub-specifications

- [`archive/subspecs/backend-external-source-access-security.md`](archive/subspecs/backend-external-source-access-security.md) — `SECURITY.EXTERNAL_SOURCE_ACCESS`, accepted after developer `./run_checks.sh` verification on 2026-09-16;
- [`archive/subspecs/backend-authentication-authorization.md`](archive/subspecs/backend-authentication-authorization.md) — `SECURITY.IDENTITY_ROLES`, `SECURITY.AUTHENTICATION`, and `SECURITY.AUTHORIZATION`, accepted after developer `./run_checks.sh` verification on 2026-09-16;
- [`archive/subspecs/backend-application-observability.md`](archive/subspecs/backend-application-observability.md) — `OBSERVABILITY.APPLICATION`, accepted after developer `./run_checks.sh` verification;
- [`archive/subspecs/backend-db-kafka-consistency.md`](archive/subspecs/backend-db-kafka-consistency.md) — `ANALYSIS.OUTBOX`, accepted after developer `./run_checks.sh` verification;
- [`archive/subspecs/backend-reliability-failure-handling.md`](archive/subspecs/backend-reliability-failure-handling.md) — `RELIABILITY.KAFKA_RETRY` and `RELIABILITY.DEAD_LETTER`, accepted after developer verification;
- [`archive/subspecs/backend-processing-flow-reconstruction.md`](archive/subspecs/backend-processing-flow-reconstruction.md) — `DIAGNOSTICS.PROCESSING_FLOW`, accepted after developer verification;
- [`archive/subspecs/backend-event-observation.md`](archive/subspecs/backend-event-observation.md) — `DIAGNOSTICS.EVENT_OBSERVATION`, accepted after developer verification;
- [`archive/subspecs/backend-results-sse-live-delivery.md`](archive/subspecs/backend-results-sse-live-delivery.md) — `RESULTS.LIVE`, accepted after developer verification;
- [`archive/subspecs/backend-source-test-generic-extraction.md`](archive/subspecs/backend-source-test-generic-extraction.md) — `COLLECTION.SOURCE_TEST`, accepted after developer verification;
- [`archive/subspecs/backend-profile-driven-scheduling.md`](archive/subspecs/backend-profile-driven-scheduling.md) — `COLLECTION.SCHEDULING`, accepted after developer verification;
- [`archive/subspecs/backend-monitoring-profile-configuration.md`](archive/subspecs/backend-monitoring-profile-configuration.md) — `CONFIGURATION.MONITORING_PROFILES`, accepted after developer verification;
- [`archive/subspecs/backend-rss-atom-extraction.md`](archive/subspecs/backend-rss-atom-extraction.md) — `COLLECTION.ADAPTERS`, accepted after developer verification;
- [`archive/subspecs/backend-results-rest-api.md`](archive/subspecs/backend-results-rest-api.md) — `RESULTS.BROWSING`, accepted after developer verification;
- [`archive/subspecs/backend-results-persistence.md`](archive/subspecs/backend-results-persistence.md) — `RESULTS.MATERIALIZATION`, accepted after developer verification;
- [`archive/subspecs/backend-configuration-persistence-rest.md`](archive/subspecs/backend-configuration-persistence-rest.md) — `CONFIGURATION.SOURCES`, accepted after developer verification;
- [`archive/subspecs/backend-operational-admin-api.md`](archive/subspecs/backend-operational-admin-api.md) — operational APIs for `COLLECTION.RUNS` and Analysis inspection, accepted after developer verification;
- [`archive/subspecs/backend-analysis-normalization-deduplication.md`](archive/subspecs/backend-analysis-normalization-deduplication.md) — `ANALYSIS.NORMALIZATION`, `ANALYSIS.DEDUPLICATION`, and `ANALYSIS.CLASSIFICATION`, accepted after developer verification;
- [`archive/subspecs/backend-collection-kafka-transport.md`](archive/subspecs/backend-collection-kafka-transport.md) — `EVENTING.PIPELINE`, accepted after developer verification;
- [`archive/subspecs/backend-collection-run-orchestration.md`](archive/subspecs/backend-collection-run-orchestration.md) — `COLLECTION.RUNS`, accepted after developer verification.

## Planned backend sub-specifications

Likely future bounded specs include:

- implementation/acceptance of the active `SECURITY.EXTERNAL_SOURCE_ACCESS` supporting specification before shared Kubernetes exposure;
- `DEPLOYMENT.KUBERNETES` + `OBSERVABILITY.INFRASTRUCTURE` — local production-style deployment and telemetry stack;
- system resilience acceptance across restart, lag, retry, recovery, and authorization boundaries.

Detailed `PRESENTATION.VIEWER_RESULTS` UI behavior belongs to `signalharvester-web` and should receive its own frontend sub-spec when frontend work resumes.
