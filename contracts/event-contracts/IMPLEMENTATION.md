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
```

The `analysis/v1` and `results/v1` directories remain reserved for later vertical-slice events.

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

## Gradle generation

The module uses the Gradle Protobuf plugin and a pinned `protoc` version from the root version catalog. Generated Java belongs under Gradle build output and is never authoritative source.

## Contract tests

`RawItemDiscoveredSerializationTest` verifies:

- representative Protobuf serialization/deserialization;
- preservation/tolerance of an unknown additive field.

The collection module now provides the first real producer adapter and a Kafka Testcontainers round trip that publishes byte-serialized `RawItemDiscovered` and decodes it with the generated contract on the consumer side.

## Current limitations

- collection publishes `RawItemDiscovered`; analysis/event-observation consumers are not implemented yet;
- no Schema Registry is configured;
- no analysis/results event schemas exist yet;
- no Event Explorer decoder is implemented yet.
