---
type: Module Contract
title: SignalHarvester module contract — Event observation
description: Public integration surface, ownership, invariants, and dependency rules for the technical Event Explorer backend.
---
# SignalHarvester module contract — Event observation

## Purpose

Own the application-level technical projection of selected published events for bounded history, live Event Explorer delivery, and later flow reconstruction.

## Owned responsibilities

- consume selected published Kafka/Protobuf events through an independent observation consumer group;
- decode transport events into an observation-owned diagnostic model;
- persist bounded, idempotent technical event history;
- expose bounded REST history queries;
- expose resumable browser-facing SSE technical-event updates;
- preserve correlation, trace, Kafka, source, profile, and item provenance needed for later flow reconstruction.

## Public integration surface

### Synchronous Java API

None. No external functional module currently calls event observation synchronously.

### REST / SSE API

The authoritative browser contract is `contracts/api-contracts/src/main/resources/openapi/signalharvester-v1.yaml`:

- `GET /api/v1/events` — bounded recent technical event history;
- `GET /api/v1/events/stream` — resumable SSE live event delivery.

### Events

The module consumes existing versioned schemas under `contracts/event-contracts/src/main/proto/`. It does not redefine them.

## Owned data

PostgreSQL schema `event_observation` contains diagnostic event materialization only. `observed_events` is keyed by a durable observation cursor and enforces unique published event identity.

## Dependencies

The module depends on the event-contract artifact, Kafka, PostgreSQL/Flyway, and Micronaut HTTP/SSE/runtime infrastructure. It has no synchronous dependency on another functional module.

## Forbidden access

- Do not query another module's private tables.
- Do not expose Kafka or PostgreSQL protocols directly to browsers.
- Do not treat diagnostic observation rows as authoritative domain state.
- Do not copy unbounded raw/normalized content bodies into event history.

## Important invariants

- event identity is idempotent by published `event_id`;
- Kafka offsets commit only after the observation transaction succeeds;
- retention is explicitly bounded by age and count;
- current collection-run correlation uses the event `correlation_id`;
- REST/SSE expose decoded JSON, not generated Protobuf types;
- an SSE resume cursor may have fallen behind retention and clients must tolerate missing expired diagnostic rows.

## Extension points

Flow reconstruction may later derive stage/edge views from this module's owned projection. Additional published event families can be observed by adding explicit decoders and tests without changing business-module ownership.
