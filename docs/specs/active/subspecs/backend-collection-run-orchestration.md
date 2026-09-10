---
type: Specification
title: SignalHarvester first collection-run orchestration
description: Current implementation sub-specification for explicit collection-run identity, enabled-source execution, best-effort failure semantics, deterministic raw-item identity, and Kafka publication orchestration.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: verification-pending
---
# SignalHarvester first collection-run orchestration

## Status

Implementation complete — Gradle and cross-module Testcontainers verification pending before archival.

This slice turns the existing persisted source configuration, bounded HTTP collection transport, and acknowledged Kafka publisher into the first real collection application use case. It deliberately stops before normalization/deduplication/analysis and before persistent monitoring-profile scheduling.

## Goal

Implement one explicit best-effort collection execution:

```text
CollectionRunRequest
    -> SourceConfigurationProvider.findEnabledSources()
    -> bounded external fetch
    -> deterministic raw-item identity
    -> RawItemEventPublisher
    -> CollectionRunResult
```

One source failure must not discard successful results from unrelated sources in the same run.

## Relationship to the umbrella specification

This sub-spec advances umbrella requirements R5, R7, R8, R20, R21, R23, and R27 and directly implements scenario S6 groundwork.

The umbrella ultimately requires persisted monitoring profiles, scheduled runs, persisted run visibility, normalization, deduplication, analysis, results, and event observation. Those remain later slices.

## Current state

Baseline available before this slice:

- PostgreSQL-backed `SourceConfigurationProvider` returns globally enabled sources;
- `SourceFetchCoordinator` performs bounded Virtual Thread HTTP fetch work;
- `RawItemEventPublisher` publishes acknowledged `RawItemDiscovered` Protobuf bytes;
- `RawItemPublicationContext` accepts caller-owned `rawItemId`, correlation, profile/category, and trace metadata;
- monitoring profiles and profile-to-source membership are not implemented yet.

Implemented in this slice:

- explicit `collectionRunId` generated per execution;
- run request with temporary caller-supplied monitoring-profile/category metadata;
- enabled-source loading through the configuration public API only;
- best-effort bounded fetch semantics that preserve deterministic source ordering;
- per-source `PUBLISHED`, `FETCH_FAILED`, and `PUBLICATION_FAILED` outcomes;
- aggregate `SUCCEEDED`, `PARTIALLY_SUCCEEDED`, and `FAILED` run status;
- collection run id reused as the raw-item event correlation id;
- deterministic SHA-256 `rawItemId` derived from source identity, requested URI, and fetched payload;
- acknowledged publication of each successfully fetched source without cancelling unrelated publications after one Kafka failure;
- source/run diagnostic logging without logging configured URLs;
- module tests for orchestration and deterministic identity;
- cross-module PostgreSQL + deterministic HTTP + Kafka Testcontainers integration coverage.

## Requirements

### R1 — every execution has an explicit run identity

`CollectionRunService.run(...)` must create a new globally unique `collectionRunId` before source work starts.

The same id must be used as the Kafka `correlationId` for every raw-item event produced by that execution.

### R2 — source selection crosses only the configuration public API

The run must obtain work through `SourceConfigurationProvider.findEnabledSources()`.

Collection must not import configuration persistence, JDBC repositories, tables, or HTTP implementation types.

Until monitoring profiles exist, this first run intentionally executes all globally enabled sources. `monitoringProfileId` and `informationCategory` are explicit request metadata rather than hidden constants. Later profile implementation must replace this temporary selection/context seam rather than duplicate orchestration.

### R3 — fetch concurrency stays bounded

External requests must continue to use `SourceFetchCoordinator` and `signalharvester.collection.max-concurrency`.

Virtual Threads do not remove the concurrency limit.

### R4 — source failures are best-effort, not run-wide fail-fast

An expected `SourceFetchException` for one source must be recorded as `FETCH_FAILED` and must not cancel unrelated in-flight or queued sources. Unexpected programming/system failures must not be silently converted into source failures.

A Kafka publication failure for one successfully fetched source must be recorded as `PUBLICATION_FAILED` and must not prevent publication attempts for other successfully fetched sources.

Coordinator interruption or an unexpected worker/system failure may still abort the application call because those are run-level failures rather than one source's terminal outcome.

### R5 — aggregate status is deterministic

Run status is derived from terminal source outcomes:

- zero failed sources -> `SUCCEEDED`;
- at least one published source and at least one failed source -> `PARTIALLY_SUCCEEDED`;
- sources were requested and none published -> `FAILED`.

A run with zero enabled sources completes as `SUCCEEDED` with an empty source-result list because no requested work failed.

### R6 — raw-item identity is stable across rediscovery

For the current one-HTTP-payload-per-source model, identical source identity + requested URI + raw payload must produce the same `rawItemId` across separate collection runs.

Fetch timestamps and `collectionRunId` must not affect `rawItemId`.

The first implementation uses SHA-256 over the source UUID, requested URI, and payload bytes. This is idempotency/deduplication groundwork, not the final normalized-item deduplication contract. Source-specific extraction may later supply finer-grained identity from stable external ids.

### R7 — event identity remains publication-specific

Each Kafka publication still receives its own new `eventId` from the Kafka mapping boundary.

`rawItemId` identifies the rediscovered content item; `eventId` identifies one event publication; `collectionRunId` identifies/correlates one execution. These identities must not be conflated.

### R8 — run results preserve useful terminal state

`CollectionRunResult` must expose at least:

- collection run id;
- temporary monitoring profile id and information category context;
- start and finish timestamps;
- aggregate run status;
- ordered per-source outcomes;
- raw item/event identifiers where those stages were reached;
- a concise failure summary for failed source outcomes.

This slice does not persist run history. Persistence becomes required when monitoring profiles/scheduling and user-visible run inspection are introduced.

### R9 — observability must avoid leaking source URLs

Run logs should include run/source/raw-item identifiers and aggregate status/counts.

Do not log complete configured source URLs because query parameters may contain credentials or tokens.

## Scenarios

### S1 — all enabled sources succeed

Two enabled sources fetch successfully and both Kafka sends are acknowledged.

The result is `SUCCEEDED`, both source results are `PUBLISHED`, both events share the run id as correlation id, and each event has its own event id.

### S2 — one source is temporarily unavailable

Three sources are enabled. The second returns HTTP 503 while the first and third succeed.

The third source still executes and publishes. The run is `PARTIALLY_SUCCEEDED`; only the second source is `FETCH_FAILED`.

### S3 — one Kafka publication fails

All source fetches succeed but one Kafka publication fails.

Publication is attempted for subsequent fetched sources. The failed source is `PUBLICATION_FAILED`, successful ones remain `PUBLISHED`, and the aggregate run is `PARTIALLY_SUCCEEDED`.

### S4 — identical content is rediscovered

The same source URL returns identical bytes in two different runs.

Both publications have distinct event ids and distinct collection run correlation ids, while the raw item id remains identical.

### S5 — no source is enabled

The provider returns no enabled sources.

The run still has an explicit id/timestamps and finishes `SUCCEEDED` with zero source results; no HTTP or Kafka work occurs.

## Non-goals

This slice does not implement:

- persisted monitoring profiles or source membership;
- a REST endpoint for manually starting a collection run;
- scheduler/cluster scheduling semantics;
- persisted collection-run history;
- collection lifecycle Kafka events such as `CollectionStarted`/`CollectionCompleted`;
- source-specific parsing into multiple external items;
- normalized content, deduplication decisions, or analysis;
- retry/DLQ policy;
- transactional outbox;
- full outbound SSRF destination policy.

## Design constraints

- Keep orchestration imperative and blocking on the existing Virtual Thread boundary.
- Keep `RawItemEventPublisher` as the Kafka boundary; orchestration must not import generated Protobuf or Micronaut Kafka types.
- Keep configuration access through `SourceConfigurationProvider` only.
- Preserve deterministic result order even though external fetches are concurrent.
- Continue unrelated source work after source-level failures.
- Do not add a generic workflow framework or premature run repository abstraction.

## Compatibility / migration

No REST or Protobuf schema changes are required.

The source coordinator changes from fail-fast batch semantics to per-source best-effort outcomes. It is internal to `modules:collection`, so this is an intentional module-internal behavioral change supporting umbrella scenario S6.

The stable raw-item hash is compatible with the existing Kafka publisher because `RawItemPublicationContext` already makes `rawItemId` caller-owned.

## Validation

Focused validation:

```bash
./gradlew :modules:collection:test :testing:integration-tests:test --no-watch-fs
```

Broader validation after the focused suite passes:

```bash
./gradlew test --no-watch-fs
```

Acceptance requires tests covering:

1. bounded concurrent fetch with deterministic order;
2. queued/in-flight work continuing after a source failure;
3. successful, partial, failed, and empty run status derivation;
4. Kafka publication continuing after one publication failure;
5. run id propagation as Kafka correlation id;
6. deterministic raw-item identity across fetch timestamps/runs;
7. cross-module persisted enabled-source -> local HTTP -> Kafka execution with a partial source failure;
8. disabled sources not participating in the run.

## Implementation tasks

1. Add explicit run request/result/status source-outcome types.
2. Change source coordination to return ordered best-effort outcomes.
3. Add deterministic raw-item identity generation.
4. Implement `CollectionRunService` over configuration, fetch, identity, and publisher boundaries.
5. Add unit/behavior tests for partial failures and identity semantics.
6. Add the cross-module Testcontainers collection-run integration scenario.
7. Synchronize current-state documentation and roadmap.
8. After developer validation is green, archive this sub-spec and move focus to normalization/deduplication/minimal analysis.
