---
type: Specification
title: SignalHarvester backend event observation
description: Durable bounded technical event history plus REST/SSE support for the Event Explorer.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: verification-pending
---
# SignalHarvester backend event observation

## Status

Verification pending — implementation is present; developer `./run_checks.sh` acceptance is still required.

## Goal

Provide a bounded, human-readable technical projection of the published Kafka event flow for the Event Explorer without making PostgreSQL observation data authoritative for business state.

## Relationship to the umbrella specification

This slice implements the backend foundation for umbrella requirements R8, R16, and R28. It also advances the event-contract requirement that the browser receive decoded JSON rather than Protobuf bytes.

## Current state

The pipeline already publishes `RawItemDiscovered`, `ItemAnalyzed`, and `ItemRejected` as versioned Protobuf events with stable envelope identity, correlation, producer, trace, and schema metadata. Results already exposes business-result REST/SSE, but no diagnostic event history exists.

## Requirements

### EO1 — independent observation consumer

Event observation must consume the existing published event topics through its own Kafka consumer group. It must not share a consumer group with Analysis or Results and must not query another module's private tables.

### EO2 — decoded bounded persistence

The module must persist an observation-owned projection containing:

- durable observation cursor;
- event id/type and envelope metadata;
- Kafka topic, partition, offset, and key;
- correlation id and traceparent where available;
- raw/normalized item, source, and monitoring-profile identities where available;
- selected human-readable payload diagnostics.

Large raw/normalized content bodies must not be copied into the diagnostic store.

Persistence must be idempotent by event id.

### EO3 — explicit retention

Diagnostic persistence must be bounded by both maximum age and maximum event count. Removing old diagnostic rows must not affect business processing or authoritative state.

### EO4 — bounded history REST API

`GET /api/v1/events` must provide bounded recent history with filters for event type, producer, Kafka topic, correlation id, collection run, item id, and trace id.

For the current pipeline, collection-run identity is the event correlation id.

### EO5 — resumable live SSE

`GET /api/v1/events/stream` must expose named `ready`, `event`, and `keepalive` SSE messages. Numeric durable observation ids are SSE cursors and `Last-Event-ID` resumes after the supplied cursor.

A fresh stream begins after the current cursor. Initial history is obtained through the REST endpoint. For a race-free browser bootstrap, establish the stream and receive `ready` before loading the REST snapshot. Buffer later `event` messages until the snapshot is applied.

A resume cursor older than retained history may only recover events that still exist. The browser must tolerate retention gaps.

### EO6 — browser-safe representation

REST/SSE responses must be JSON and must not require browser-side Protobuf decoding or direct Kafka access.

## Non-goals

This slice does not implement:

- visual flow graph reconstruction;
- OpenTelemetry/Tempo trace lookup;
- Kafka replay or unlimited historical browsing;
- observation of internal method calls that are not published events;
- new Kafka event families solely to make the explorer look more detailed.

## Design constraints

- Event observation owns its schema and adapters.
- Published Protobuf schemas remain the transport source of truth.
- Observation storage is diagnostic materialization only.
- Kafka offsets commit only after durable observation persistence succeeds.
- SSE JDBC polling runs on the blocking executor while the controller remains reactive.

## Validation

Acceptance requires:

1. mapper tests for all currently published event families;
2. server-level REST contract/offload coverage;
3. Kafka + PostgreSQL integration coverage for decoding, idempotency, and bounded retention;
4. OpenAPI coverage for REST/SSE contracts;
5. canonical `./run_checks.sh` passing in the developer environment.
