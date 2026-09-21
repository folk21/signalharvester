---
type: Module Overview
title: SignalHarvester event observation module
description: Developer entry point for the bounded technical Event Explorer backend.
---
# SignalHarvester event observation module

Read [`contract.md`](contract.md) first for authoritative ownership and integration boundaries.

## Current implementation

The module observes the currently published `RawItemDiscovered`, `ItemAnalyzed`, and `ItemRejected` Protobuf event families through its own Kafka consumer group. Deterministically invalid records are sent directly to the Event Observation DLQ; recording failures use bounded retry before terminal DLQ handling. Listener-thread interruption is lifecycle cancellation and escapes before retry exhaustion or DLQ classification while preserving the interrupt flag. Any exception that escapes the listener rewinds every partition from the failed Kafka poll to its first polled offset before normal consumption resumes; repeated earlier records remain safe through event-id idempotency. The listener uses Micronaut `SYNC_PER_RECORD` and returns normally only after recording or acknowledged dead-letter publication; Micronaut performs the synchronous source-offset commit afterward. DLQ publication failure escapes before successful completion, while framework commit failures remain outside the recording retry/DLQ boundary and may cause normal at-least-once redelivery. It records selected decoded metadata in the `event_observation` PostgreSQL schema with event-id idempotency and configurable count/age retention. Persistence uses Micronaut-managed Jdbi inside application-owned transactions, with named bindings and module-owned classpath SQL resources. Retention enforcement remains in the recording transaction but uses one bounded prune statement after each insert rather than separate age/count deletes.

`GET /api/v1/events` provides bounded history filters for event type, producer, topic, correlation/collection run, item identity, and trace id. `GET /api/v1/events/stream` provides `ready`, `event`, and `keepalive` SSE messages with durable numeric observation cursors and `Last-Event-ID` resume. Generic demand, delayed scheduling, and task-cancellation lifecycle is delegated to the JDK-only `common` polling utility; Event Observation retains cursor, query, and SSE framing semantics. Client cancellation cancels the next scheduled poll and requests interruption of the active blocking history poll.

The diagnostic projection intentionally excludes large raw and normalized content bodies. It is not a source of truth for business modules and it does not replace distributed tracing or the Kafka log.

Accepted controlled recovery reads one known Event Observation DLQ position and re-records the stored original key/payload through the same decoder/recorder while preserving the original source topic/partition/offset. It does not republish the shared business event, so repairing this diagnostic projection cannot trigger unrelated Analysis or Results processing.

`GET /api/v1/flows/collection-runs/{collectionRunId}` reconstructs a bounded run graph, and `/api/v1/flows/collection-runs/{collectionRunId}/items/{itemId}` reconstructs one raw/normalized item branch within that run. Graph stages explicitly mark evidence as observed, Kafka-observed, derived, or not observed. `ProcessingFlowService` owns retained-event query/scoping, while the package-private `ProcessingFlowReconstructor` owns deterministic graph construction. Results persistence is currently shown as `NOT_OBSERVED` rather than inferred as successful.

## Read next

- [`contract.md`](contract.md) — authoritative module boundary and invariants
- [`../../docs/specs/archive/subspecs/backend-processing-flow-reconstruction.md`](../../docs/specs/archive/subspecs/backend-processing-flow-reconstruction.md) — accepted processing-flow reconstruction history
- [`../AGENTS.md`](../AGENTS.md) — shared module-development rules
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) — system architecture
