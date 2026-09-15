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
- own Results JDBC transactions and PostgreSQL schema;
- expose bounded read-only REST browsing/detail access over analyzed results;
- expose resumable browser live delivery over committed analyzed projections through SSE.

## Public integration surface

### Synchronous Java API

None. Results currently has no synchronous functional-module consumer, so no `api/` package is published.

### REST API

The authoritative contract is `contracts/api-contracts/src/main/resources/openapi/signalharvester-v1.yaml`:

- `GET /api/v1/results` — compact newest-first analyzed-result feed with bounded filters;
- `GET /api/v1/results/{normalizedItemId}?monitoringProfileId=...` — detailed profile-scoped analyzed result;
- `GET /api/v1/results/stream` — filtered resumable SSE updates over committed analyzed projections.

The REST/SSE surface exposes Results-owned response DTOs only. It does not expose JDBC rows, persistence adapters, or generated Protobuf classes.

### Events

Consumes versioned `ItemAnalyzed` and `ItemRejected` messages from `contracts/event-contracts/src/main/proto/io/signalharvester/events/analysis/v1/`.

Generated Protobuf classes remain inside the Kafka adapter boundary.

## Owned data

PostgreSQL schema `results`, created by `db/migration/results/V4__create_result_projections.sql`:

- `results.analyzed_items` — one current projection per monitoring-profile/logical-item identity;
- `results.analyzed_item_attributes` — normalized result attributes;
- `results.analyzed_item_tags` — ordered analysis tags;
- `results.rejected_items` — terminal rejection records keyed by upstream source-event identity;
- `results.live_result_cursors` plus `results.live_result_event_id_seq` — one durable monotonic live-delivery cursor per current logical analyzed result.

Other modules must not query or mutate these tables directly.

## Dependencies

No synchronous functional-module dependency is required. Results depends only on shared infrastructure/framework libraries and the versioned event-contract artifact.

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
- deterministic transport/key/mapping failures are dead-lettered without retry; projection failures use bounded retry;
- Kafka offsets are committed only after the Results transaction commits successfully or terminal Results dead-letter publication is acknowledged;
- a failed Results DLQ publication leaves the source offset uncommitted;
- exhausted projection failures advance the consumed offset only after acknowledged Results dead-letter publication;
- write and read repositories participate in application-owned JDBC transactions;
- result-feed limit is bounded to `1..200` and ordered deterministically newest first;
- feed retrieval must avoid per-result N+1 persistence reads;
- detailed result lookup is profile-scoped because normalized identity is profile-scoped;
- Results does not provide distributed exactly-once processing; it achieves retry safety through idempotent projection keys.

## Extension points

Add rejected-result operational inspection only for a concrete consumer. Extend live delivery only when a concrete requirement needs richer replay semantics than the current bounded current-state cursor model. Create a published Java `api/` package only if a real synchronous cross-module consumer appears.
