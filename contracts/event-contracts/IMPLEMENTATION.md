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
failure/v1/dead-letter-event.proto
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

`RawItemDiscovered` carries the raw item identity, source/profile provenance, information category, optional source identifier/title, URL, raw text content, content type, optional publication timestamp, and an additive immutable keyword Analysis-settings snapshot captured by Collection. Field 12 is optional at the wire level for backward compatibility with already-published version-one events.

This is a Kafka transport contract, not the collection or analysis domain model.

## Analysis events

`ItemAnalyzed` carries normalized item identity/content, source/profile provenance, deterministic relevance/classification/score/tags, analyzer identity, and linkage back to the raw source event. `ItemRejected` carries the raw/normalized identity and a machine-readable rejection reason; the first implemented reason is `DUPLICATE`.

These are Kafka transport contracts. Analysis core logic maps to and from module-owned Java models at the adapter boundary.

## Dead-letter event

`DeadLetterEvent` captures terminal consumer failures for Analysis, Results, and Event Observation. It carries a deterministic dead-letter identity derived from consumer group and source Kafka position, the complete source topic/partition/offset/key/value needed for deliberate replay, bounded failure diagnostics, attempt count, and whether the exhausted path was retryable. Keeping the original serialized value preserves event correlation and trace metadata whenever the source event was decodable, while malformed source bytes remain inspectable without inventing missing metadata.

## Gradle generation

The module uses the Gradle Protobuf plugin and a pinned `protoc` version from the root version catalog. Generated Java belongs under Gradle build output and is never authoritative source.

## Contract tests

`RawItemDiscoveredSerializationTest`, `AnalysisEventSerializationTest`, and `DeadLetterEventSerializationTest` verify representative Protobuf serialization/deserialization. The published business-event tests also cover preservation/tolerance of unknown additive fields.

Collection provides the real `RawItemDiscovered` producer adapter. Analysis provides the first real consumer plus `ItemAnalyzed`/`ItemRejected` producers. Event Observation consumes all three published event families through an independent Kafka consumer group and decodes them into its own diagnostic read model. Kafka/Testcontainers integration coverage exercises the byte-serialized flow across these boundaries and verifies representative Event Explorer decoding.

## Current limitations

- no Schema Registry is configured;
- results event schemas are not implemented yet;
- automatic dead-letter replay is intentionally not implemented;
- compatibility fixtures for an evolved published schema are not implemented yet.
