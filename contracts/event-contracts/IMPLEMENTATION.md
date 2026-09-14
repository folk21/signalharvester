---
type: Implementation
title: SignalHarvester event-contract implementation
description: Current physical Protobuf source layout, generated-code boundary, implemented schemas, and contract tests.
---
# SignalHarvester event-contract implementation

## Scope

This document describes the current implementation of `contracts:event-contracts`. Compatibility rules and intended evolution remain owned by the active event-contract sub-spec until they become stable architecture.

## Source layout

Versioned `.proto` sources live under `src/main/proto/io/signalharvester/events/`.

Implemented schemas:

```text
common/v1/event-envelope.proto
collection/v1/raw-item-discovered.proto
analysis/v1/item-analyzed.proto
analysis/v1/item-rejected.proto
```

The `results/v1` directory remains reserved for later result-domain events.

## Event envelope

`EventEnvelope` currently carries:

- event identity;
- logical event type;
- occurrence timestamp;
- correlation identity;
- W3C `traceparent` when available;
- logical producer;
- schema version.

The payload contract stays explicit rather than storing domain data in an opaque envelope field.

## Raw item event

`RawItemDiscovered` carries the raw item identity, source/profile provenance, information category, optional source identifier/title, URL, raw text content, content type, and optional publication timestamp.

This is a Kafka transport contract, not the collection or analysis domain model.

## Analysis events

`ItemAnalyzed` carries normalized item identity/content, source/profile provenance, deterministic relevance/classification/score/tags, analyzer identity, and linkage back to the raw source event. `ItemRejected` carries the raw/normalized identity and a machine-readable rejection reason; the first implemented reason is `DUPLICATE`.

These are Kafka transport contracts. Analysis core logic maps to and from module-owned Java models at the adapter boundary.

## Gradle generation

The module uses the Gradle Protobuf plugin and a pinned `protoc` version from the root version catalog. Generated Java belongs under Gradle build output and is never authoritative source.

## Contract tests

`RawItemDiscoveredSerializationTest` and `AnalysisEventSerializationTest` verify representative Protobuf serialization/deserialization and preservation/tolerance of unknown additive fields.

Collection provides the real `RawItemDiscovered` producer adapter. Analysis provides the first real consumer plus `ItemAnalyzed`/`ItemRejected` producers. Event Observation consumes all three published event families through an independent Kafka consumer group and decodes them into its own diagnostic read model. Kafka/Testcontainers integration coverage exercises the byte-serialized flow across these boundaries and verifies representative Event Explorer decoding.

## Current limitations

- no Schema Registry is configured;
- results event schemas are not implemented yet;
- compatibility fixtures for an evolved published schema are not implemented yet.
