---
type: Module Contract
title: SignalHarvester module contract — Event observation
description: Public integration surface, ownership, invariants, and dependency rules for the technical Event Explorer backend.
---
# SignalHarvester module contract — Event observation

## Purpose

Own the application-level technical projection of selected published events for bounded history, live Event Explorer delivery, and processing-flow reconstruction.

## Owned responsibilities

- consume selected published Kafka/Protobuf events through an independent observation consumer group;
- decode transport events into an observation-owned diagnostic model;
- persist bounded, idempotent technical event history;
- expose bounded REST history queries;
- expose resumable browser-facing SSE technical-event updates;
- preserve correlation, trace, Kafka, source, profile, and item provenance needed for flow reconstruction;
- reconstruct bounded collection-run and run-scoped item graphs on read from retained observation evidence;
- expose controlled owner-specific dead-letter inspection and replay.

## Public integration surface

### Synchronous Java API

None. Event Observation currently publishes no synchronous cross-module Java API.

### REST / SSE API

Authoritative schema: `contracts/api-contracts/src/main/resources/openapi/signalharvester-v1.yaml`.

Event Observation owns the Event Explorer route family under `/api/v1/events`, resumable live delivery at `/api/v1/events/stream`, Processing Flow routes under `/api/v1/flows/collection-runs`, and controlled dead-letter inspection/replay under `/api/v1/admin/event-observation/dead-letters`.

### Events

Event Observation consumes `RawItemDiscovered`, `ItemAnalyzed`, and `ItemRejected` through an independent observation consumer group. Terminal Event Observation consumer failures publish `DeadLetterEvent`.

Authoritative event sources:

- `contracts/event-contracts/src/main/proto/io/signalharvester/events/collection/v1/raw-item-discovered.proto`;
- `contracts/event-contracts/src/main/proto/io/signalharvester/events/analysis/v1/item-analyzed.proto`;
- `contracts/event-contracts/src/main/proto/io/signalharvester/events/analysis/v1/item-rejected.proto`;
- `contracts/event-contracts/src/main/proto/io/signalharvester/events/failure/v1/dead-letter-event.proto`;
- `contracts/event-contracts/src/main/proto/io/signalharvester/events/common/v1/event-envelope.proto` — shared envelope used by the observed normal pipeline events.

The module does not redefine these schemas, and generated Protobuf classes remain inside the Kafka adapter boundary.

## Owned data

PostgreSQL schema `event_observation` is owned by migrations under `modules/event-observation/src/main/resources/db/migration/event_observation/`. `observed_events` contains diagnostic event materialization only, is keyed by a durable observation cursor, and enforces unique published event identity.

## Dependencies

No synchronous dependency on another functional module is required.

The module depends on the event-contract artifact, Kafka, PostgreSQL/Flyway, Micronaut-managed Jdbi, and Micronaut HTTP/SSE/runtime infrastructure.

## Forbidden access

- Do not query another module's private tables.
- Do not expose Kafka or PostgreSQL protocols directly to browsers.
- Do not treat diagnostic observation rows as authoritative domain state.
- Do not copy unbounded raw/normalized content bodies into event history.

## Important invariants

- event identity is idempotent by published `event_id`;
- Event Observation repositories execute only inside application-owned Micronaut transactions; Jdbi does not own business transaction boundaries;
- the Event Observation Kafka listener uses Micronaut `SYNC_PER_RECORD` and must not call `Consumer.commitSync()` directly;
- deterministic transport/key/mapping failures are dead-lettered without retry; recording failures use bounded retry;
- normal listener completion occurs only after the observation transaction succeeds or terminal Event Observation dead-letter publication is acknowledged; Micronaut owns the synchronous per-record source-offset commit afterward;
- failed Event Observation DLQ publication must escape before successful listener completion, while framework commit failure remains outside recording retry/DLQ classification and may result in at-least-once redelivery;
- retention is explicitly bounded by age and count;
- current collection-run correlation uses the event `correlation_id`;
- REST/SSE expose decoded JSON, not generated Protobuf types;
- an SSE resume cursor may have fallen behind retention and clients must tolerate missing expired diagnostic rows;
- flow lineage links terminal events to raw discoveries by published `sourceEventId`;
- reconstructed stages explicitly identify observed, derived, and currently unobserved evidence;
- Results persistence is not claimed as completed until an observation signal proves it;
- operator recovery validates the current Event Observation consumer group and observed source topic, preserves original source transport metadata, and reuses the normal decoder/recorder without shared-topic republish or source-offset mutation.

## Extension points

Additional published event families may enrich the current stage/edge reconstruction when they have value beyond visualization alone. Distributed-trace lookup may later augment, but must not replace, the application-level evidence model.
