---
type: Module Overview
title: SignalHarvester event observation module
description: Developer entry point for the bounded technical Event Explorer backend.
---
# SignalHarvester event observation module

Read [`contract.md`](contract.md) first for authoritative ownership and integration boundaries.

## Current implementation

The module observes the currently published `RawItemDiscovered`, `ItemAnalyzed`, and `ItemRejected` Protobuf event families through its own Kafka consumer group. Deterministically invalid records are sent directly to the Event Observation DLQ; recording failures use bounded retry before terminal DLQ handling. Source offsets advance only after recording or acknowledged dead-letter publication. It records selected decoded metadata in the `event_observation` PostgreSQL schema with event-id idempotency and configurable count/age retention.

`GET /api/v1/events` provides bounded history filters for event type, producer, topic, correlation/collection run, item identity, and trace id. `GET /api/v1/events/stream` provides `ready`, `event`, and `keepalive` SSE messages with durable numeric observation cursors and `Last-Event-ID` resume.

The diagnostic projection intentionally excludes large raw and normalized content bodies. It is not a source of truth for business modules and it does not replace distributed tracing or the Kafka log.

`GET /api/v1/flows/collection-runs/{collectionRunId}` reconstructs a bounded run graph, and `/api/v1/flows/collection-runs/{collectionRunId}/items/{itemId}` reconstructs one raw/normalized item branch within that run. Graph stages explicitly mark evidence as observed, Kafka-observed, derived, or not observed. Results persistence is currently shown as `NOT_OBSERVED` rather than inferred as successful.

## Read next

- [`contract.md`](contract.md) — authoritative module boundary and invariants
- [`../../docs/specs/active/subspecs/backend-processing-flow-reconstruction.md`](../../docs/specs/active/subspecs/backend-processing-flow-reconstruction.md) — active processing-flow acceptance
- [`../AGENTS.md`](../AGENTS.md) — shared module-development rules
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) — system architecture
