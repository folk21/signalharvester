---
type: Module Contract
title: SignalHarvester module contract — Results
description: Results persistence/read ownership, asynchronous integration surface, public REST/SSE boundaries, invariants, and extension rules.
---
# SignalHarvester module contract — Results

## Purpose

Own durable user-facing analyzed-result projections and their read boundary, while retaining terminal rejection state for reliability/operations.

## Owned responsibilities

- consume terminal `ItemAnalyzed` and `ItemRejected` integration events;
- map transport events into Results-owned models;
- materialize analyzed results idempotently;
- retain rejected source-event outcomes without creating duplicate rows on redelivery;
- own Results application transactions and PostgreSQL schema;
- expose backward-compatible read-only REST browsing/detail access with bounded filters, keyset continuation, and indexed text search;
- expose resumable browser live delivery over committed analyzed projections through SSE;
- expose controlled owner-specific dead-letter inspection and replay.

## Public integration surface

### Synchronous Java API

None. Results currently has no synchronous functional-module consumer, so no `api/` package is published.

### REST / SSE API

Authoritative schema: `contracts/api-contracts/src/main/resources/openapi/signalharvester-v1.yaml`.

Results owns the analyzed-result route family under `/api/v1/results`, resumable live delivery at `/api/v1/results/stream`, and controlled dead-letter inspection/replay under `/api/v1/admin/results/dead-letters`.

The REST/SSE surface exposes Results-owned response DTOs only. It does not expose JDBC rows, persistence adapters, or generated Protobuf classes.

### Events

Results consumes `ItemAnalyzed` and `ItemRejected`. Terminal Results consumer failures publish `DeadLetterEvent`.

Authoritative event sources:

- `contracts/event-contracts/src/main/proto/io/signalharvester/events/analysis/v1/item-analyzed.proto`;
- `contracts/event-contracts/src/main/proto/io/signalharvester/events/analysis/v1/item-rejected.proto`;
- `contracts/event-contracts/src/main/proto/io/signalharvester/events/failure/v1/dead-letter-event.proto`;
- `contracts/event-contracts/src/main/proto/io/signalharvester/events/common/v1/event-envelope.proto` — shared envelope used by the normal Analysis events above.

Generated Protobuf classes remain inside the Kafka adapter boundary.

## Owned data

PostgreSQL schema `results` is owned by migrations under `modules/results/src/main/resources/db/migration/results/`:

- `results.analyzed_items` — one current projection per Monitoring Profile/logical-item identity;
- `results.analyzed_item_attributes` — normalized result attributes;
- `results.analyzed_item_tags` — ordered analysis tags;
- `results.rejected_items` — terminal rejection records keyed by upstream source-event identity;
- `results.live_result_cursors` plus `results.live_result_event_id_seq` — one durable monotonic live-delivery cursor per current logical analyzed result;
- Results-owned deterministic browse-order and GIN full-text indexes support REST browsing.

Other modules must not query or mutate these tables directly.

## Dependencies

No synchronous functional-module dependency is required.

Results depends on the stable `common` SQL-resource and demand-driven polling lifecycle utilities, shared infrastructure/framework libraries, and the versioned event-contract artifact. Results SQL, cursor semantics, event mapping, and row mapping remain module-local.

## Forbidden access

Do not import Analysis implementation/application/persistence types or read the `analysis` schema. Do not expose JDBC rows or generated Protobuf classes as REST/module API models.

## Important invariants

- analyzed result identity is `(monitoringProfileId, normalizedItemId)`;
- rejection idempotency identity is `sourceEventId`;
- repeated terminal publication must not create duplicate logical result rows;
- redelivery of the same `analysisEventId` must not advance the visible live cursor;
- a new analysis event for an existing logical result advances its live cursor in the same transaction as the projection update;
- a fresh SSE connection starts after the current cursor; `Last-Event-ID` resumes after a previously delivered cursor;
- race-free browser bootstrap opens SSE through `ready` before loading the REST snapshot, then merges buffered/live updates by logical result identity;
- a resume cursor ahead of current durable state is normalized to the current watermark rather than starving future delivery;
- live delivery exposes current projections, not an append-only history, so multiple disconnected updates to one logical result may collapse to the latest projection;
- SSE client cancellation cancels pending scheduled polling and requests interruption of the active blocking poll task; cancellation-induced query unwind is not a client-visible stream failure after cancellation;
- the Results Kafka listener uses Micronaut `SYNC_PER_RECORD` and must not call `Consumer.commitSync()` directly;
- deterministic transport/key/mapping failures are dead-lettered without retry; projection failures use bounded retry;
- listener-thread interruption is a lifecycle cancellation signal: interrupted processing escapes retry/DLQ classification and preserves the interrupt flag;
- when any listener failure escapes, the per-listener exception handler rewinds every partition from the failed Kafka poll to its first polled offset before normal consumption resumes, so uninvoked records remain eligible for redelivery;
- failed-poll rewind may deliberately reprocess earlier records from the same poll and therefore relies on Results projection idempotency;
- normal listener completion occurs only after the Results transaction commits successfully or terminal Results dead-letter publication is acknowledged; Micronaut owns the synchronous per-record source-offset commit afterward;
- a failed Results DLQ publication must escape before successful listener completion, while framework commit failure remains outside Results application retry/DLQ classification and may result in at-least-once redelivery;
- exhausted projection failures become eligible for framework offset commit only after acknowledged Results dead-letter publication;
- write and read repositories participate in application-owned transactions;
- result-feed limit is bounded to `1..200` and ordered by `analyzedAt DESC`, `monitoringProfileId ASC`, `normalizedItemId ASC`;
- page cursors encode the last sort key and are bound to all query criteria except page size;
- REST page cursors are distinct from numeric SSE `Last-Event-ID` cursors;
- search is bounded and uses PostgreSQL full-text search over title and normalized content within Results-owned tables;
- feed retrieval must avoid per-result N+1 persistence reads;
- detailed result lookup is profile-scoped because normalized identity is profile-scoped;
- Results does not provide distributed exactly-once processing; it achieves retry safety through idempotent projection keys;
- operator recovery validates the current Results consumer group and allowed terminal Analysis topic, requires exact dead-letter-id confirmation, and reuses the normal decoder/projector without shared-topic republish or offset mutation.

## Extension points

Add rejected-result operational inspection only for a concrete consumer. Extend live delivery only when a concrete requirement needs richer replay semantics than the current bounded current-state cursor model. Create a published Java `api/` package only if a real synchronous cross-module consumer appears.
