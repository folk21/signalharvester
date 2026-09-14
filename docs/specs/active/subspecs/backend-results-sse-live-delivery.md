---
type: Specification
title: Backend Results SSE live delivery
description: Add cluster-safe, resumable Server-Sent Events delivery over committed Results projections.
document_role: subspec
spec_status: verification-pending
parent: ../spec-signal-harvester-platform.md
---
# Backend Results SSE live delivery

## Status

Verification pending — implementation is present and awaits the developer `./run_checks.sh` acceptance run.

## Goal

Deliver newly committed analyzed results to an already-open browser page without refresh. Keep Results as the owner of the browser-facing read model. Support automatic SSE reconnection without exposing Kafka to the browser.

## Relationship to the umbrella specification

This slice implements R13 and extends the existing R12/R15 Results read boundary with live delivery.

## Baseline before this slice

Results consumes terminal Analysis events and materializes analyzed projections in PostgreSQL. The public REST API supports bounded browsing and detail lookup. There is no live browser delivery endpoint.

## Requirements

### R1 — Results owns live delivery

Expose `GET /api/v1/results/stream` as a Results-owned `text/event-stream` endpoint.

The browser must not connect to Kafka or PostgreSQL directly. The SSE adapter must expose Results-owned response models only.

### R2 — only committed analyzed projections become live results

A live-result cursor is updated in the same PostgreSQL transaction as the analyzed Results projection.

Rejected outcomes remain internal operational state and are not emitted by this product feed.

Kafka redelivery of the same `analysisEventId` must not allocate a new visible live cursor.

### R3 — live cursors are durable and cluster-safe

The live-delivery cursor is persisted in the Results schema. It must work when the Kafka consumer and SSE connection are served by different backend replicas.

Keep one current cursor row per `(monitoringProfileId, normalizedItemId)` rather than an unbounded append-only SSE history.

A newer analysis event for the same logical result replaces that row with a newer monotonic cursor.

### R4 — connections without a resume cursor start after current state

A client without `Last-Event-ID` receives a `ready` event containing the current durable cursor, then only later changes.

Initial result browsing remains the responsibility of `GET /api/v1/results`. This avoids duplicating the initial feed over SSE.

For race-free browser bootstrap, open SSE first and wait for `ready`, then load the REST snapshot while buffering subsequent `result` events. Apply the REST snapshot and then the buffered/current live updates by logical result identity. Loading REST first and opening SSE afterward can miss a result committed between those two operations.

### R5 — automatic reconnection resumes after Last-Event-ID

Every SSE event has a numeric `id` derived from the durable live cursor.

When the browser reconnects with `Last-Event-ID`, the backend emits matching current result projections whose live cursor is newer than that value.

Cursor polling is bounded. When filters exclude inspected updates, the internal cursor may advance across that inspected durable range so the backend does not rescan unrelated events forever.

If a supplied resume cursor is ahead of the current durable high watermark, normalize it back to the current high watermark. This prevents a stale cursor from another database lifecycle from starving the stream indefinitely.

The live cursor table stores current projections, not every intermediate update. If the same logical result changes several times while a client is disconnected, reconnection may deliver only its latest current projection.

### R6 — live filters match the bounded Results feed dimensions

The live endpoint supports optional filters for:

- monitoring profile;
- source;
- information category;
- relevance;
- classification.

The same URL filters remain effective when browser `EventSource` reconnects automatically.

### R7 — streaming work does not block the Netty event loop

The controller returns a Reactive Streams `Publisher<Event<...>>`.

Blocking JDBC polling runs on Micronaut's blocking executor. Poll scheduling uses the scheduled executor. Do not put the streaming controller itself behind a blocking `@ExecuteOn` boundary.

The publisher must honor downstream demand and release scheduled work when the client cancels the connection.

### R8 — idle streams remain healthy

Emit bounded `keepalive` SSE events when no result update has been sent for the configured keepalive interval.

Expose the browser reconnect delay through the SSE `retry` field on ready/result events.

Poll interval, keepalive interval, reconnect delay, and batch size are runtime-configurable. Batch size is bounded to at most 200.

## SSE wire behavior

Named events:

- `ready` — establishes the current cursor for a fresh connection; `result` is null;
- `result` — contains one compact `ResultSummary` payload;
- `keepalive` — keeps an idle connection active and advances the client cursor across already-inspected nonmatching changes; `result` is null.

Each event contains:

- SSE `id`: decimal durable cursor;
- JSON `data.cursor`: the same cursor;
- JSON `data.result`: compact result or null.

## Compatibility / migration

Add Flyway migration `V8` under the Results migration location.

The migration creates a Results-owned sequence and one current live-cursor row per analyzed projection. Existing analyzed projections are backfilled so future updates and duplicate-event detection start from a consistent durable state.

No Kafka/Protobuf schema changes are required. Existing REST result endpoints remain compatible.

## Non-goals

- WebSocket support;
- direct Kafka-to-browser streaming;
- rejected-result live delivery;
- an unlimited historical event log;
- cursor pagination for the regular Results REST feed;
- event-explorer delivery, which belongs to the event-observation slice;
- authentication/authorization changes.

## Validation

The slice is ready for acceptance when:

1. server-level tests verify SSE framing, initial `ready`, `result`, resume by `Last-Event-ID`, filter binding, cursor validation, and blocking-executor JDBC polling;
2. PostgreSQL tests verify filtered live polling and duplicate `analysisEventId` cursor idempotency;
3. Kafka/PostgreSQL coverage verifies only new analysis-event identities advance the live cursor;
4. a cross-module black-box test opens SSE before collection and observes the committed analyzed result through the public live endpoint;
5. OpenAPI and owning Results/current-state documentation describe the implemented wire behavior and runtime settings;
6. `./run_checks.sh` passes in the developer environment.

## Implementation tasks

1. Add the durable Results live-cursor migration.
2. Update analyzed projection writes to advance the cursor transactionally and idempotently.
3. Add bounded live-query application/persistence boundaries.
4. Add the backpressure-aware Results SSE adapter with ready/result/keepalive events.
5. Extend OpenAPI and focused module/cross-module tests.
6. Synchronize current-state documentation and specification lifecycle.
