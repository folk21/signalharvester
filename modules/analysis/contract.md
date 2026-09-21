---
type: Module Contract
title: SignalHarvester module contract — Analysis
description: Public integration surface, ownership, invariants, and dependency rules for the analysis module.
---
# SignalHarvester module contract — Analysis

## Purpose

Own post-discovery normalization, profile-scoped deduplication, deterministic analysis, terminal analysis-event publication, and bounded operational inspection.

## Owned responsibilities

- raw-item transport mapping;
- normalization and logical-item identity;
- durable deduplication claims;
- analysis/classification;
- terminal analysis event publication;
- read-only operational inspection of normalized-item claims;
- controlled owner-specific dead-letter inspection and replay.

## Public integration surface

### Synchronous Java API

None. Analysis currently publishes no synchronous cross-module Java API.

`AnalysisItemInspectionQuery` and `AnalysisItemInspection` live under `analysis.application` as an internal read-only application boundary used by the Analysis-owned HTTP adapter and tests. The raw-item processing path is also intentionally internal and event-driven.

`RawItemProcessor`, `ContentAnalyzer`, `ContentNormalizer`, `AnalysisOutbox`, inspection queries, and repository interfaces are internal ports used to structure the module implementation, not published module contracts.

### REST / SSE API

Authoritative schema: `contracts/api-contracts/src/main/resources/openapi/signalharvester-v1.yaml`.

Analysis owns the bounded inspection route family under `/api/v1/admin/analysis/items` and controlled dead-letter inspection/replay under `/api/v1/admin/analysis/dead-letters`.

Implementation adapters live under `modules/analysis/src/main/java/io/signalharvester/analysis/http/`.

The operational inspection controller depends on the internal `AnalysisItemInspectionQuery` application boundary, not directly on persistence.

### Events

Analysis consumes `RawItemDiscovered`, including the effective Analysis-settings snapshot when present, and produces `ItemAnalyzed` or `ItemRejected`. Terminal Analysis consumer failures publish `DeadLetterEvent`.

Authoritative event sources:

- `contracts/event-contracts/src/main/proto/io/signalharvester/events/collection/v1/raw-item-discovered.proto`;
- `contracts/event-contracts/src/main/proto/io/signalharvester/events/analysis/v1/item-analyzed.proto`;
- `contracts/event-contracts/src/main/proto/io/signalharvester/events/analysis/v1/item-rejected.proto`;
- `contracts/event-contracts/src/main/proto/io/signalharvester/events/failure/v1/dead-letter-event.proto`;
- `contracts/event-contracts/src/main/proto/io/signalharvester/events/common/v1/event-envelope.proto` — shared envelope used by the normal pipeline events above.

Kafka/Protobuf adapters remain internal implementation details.

## Owned data

- PostgreSQL schema `analysis` and migrations under `modules/analysis/src/main/resources/db/migration/analysis/`;
- normalized-item/deduplication claim state;
- transactional terminal-event outbox rows and dispatcher lease/publication metadata.

Other modules must not query Analysis tables directly.

## Dependencies

No synchronous dependency on another functional module is currently required.

Collection-to-Analysis processing is asynchronous through Kafka contracts.

## Forbidden access

Consumers must not import Analysis `application`, `normalization`, `rules`, `event`, `outbox`, `persistence`, `configuration`, or `http` packages.

Do not turn internal strategy/repository interfaces into published APIs solely because they are interfaces.

## Important invariants

- deduplication identity is scoped by Monitoring Profile;
- deterministic classification uses the immutable settings snapshot carried by the raw event and does not synchronously query Configuration;
- deployment-global keyword rules are a compatibility fallback only for legacy raw events without a settings snapshot;
- the raw Kafka listener uses Micronaut `SYNC_PER_RECORD` and must not call `Consumer.commitSync()` directly;
- deterministic decode/key/mapping failures are dead-lettered without retry, while application failures use a bounded retry policy;
- listener-thread interruption is a lifecycle cancellation signal: interrupted processing escapes retry/DLQ classification and preserves the interrupt flag;
- when any listener failure escapes, the per-listener exception handler rewinds every partition from the failed Kafka poll to its first polled offset before normal consumption resumes, so uninvoked records remain eligible for redelivery;
- failed-poll rewind may deliberately reprocess earlier records from the same poll and therefore relies on Analysis deduplication/outbox idempotency;
- normal listener completion occurs only after the Analysis state/outbox transaction commits or acknowledged Analysis dead-letter publication; Micronaut owns the synchronous per-record source-offset commit afterward;
- DLQ publication failure must escape before successful listener completion, while framework commit failure remains outside Analysis application retry/DLQ classification and may result in at-least-once redelivery;
- deduplication writes require an active application-owned database transaction and Jdbi adapters must not self-commit;
- a successful raw-item transaction commits the deduplication claim/update and exact serialized terminal event outbox row atomically before the listener can complete successfully;
- Kafka publication happens outside database transactions through bounded expiring outbox leases;
- each claimed outbox row renews its exact-token lease immediately before Kafka publication; a stale owner that can no longer renew must not publish the row;
- pre-publication renewal completes before Kafka I/O and prevents local batch queueing from consuming a later row's ownership window; it does not provide distributed exactly-once delivery or guarantee that one Kafka send cannot outlive the renewed lease;
- outbox worker interruption is lifecycle cancellation, not ordinary publication failure: it escapes before retry metadata is written for the interrupted operation, preserves/restores the interrupt flag, and stops later rows in the current claimed batch;
- a post-ack publication-marker failure may republish the same event id/payload, so downstream persistence remains idempotent;
- terminal input failures become eligible for framework offset commit only after acknowledged Analysis dead-letter publication;
- operator recovery validates the current Analysis consumer group and raw input topic, requires exact dead-letter-id confirmation, reuses the normal decoder/processor, and never republishes the shared raw topic or rewrites source offsets.

## Extension points

New analyzers and normalization/persistence/event adapters should normally remain internal ports. Publish a new synchronous API only for a concrete external/module caller.
