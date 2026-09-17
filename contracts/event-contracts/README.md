---
type: Module Overview
title: SignalHarvester event contracts
description: Entry point for versioned Kafka Protocol Buffers schemas and event-contract compatibility rules.
---
# SignalHarvester event contracts

## Canonical sources

Kafka integration-event schemas live under:

```text
src/main/proto/io/signalharvester/events/
    common/v1/
    collection/v1/
    analysis/v1/
    failure/v1/
    results/v1/
```

`.proto` files are authoritative. Generated Java classes are build artifacts.

## Current state

The first `v1` schemas are implemented:

- `common/v1/event-envelope.proto`;
- `collection/v1/raw-item-discovered.proto`;
- `analysis/v1/item-analyzed.proto`;
- `analysis/v1/item-rejected.proto`;
- `failure/v1/dead-letter-event.proto`.

Gradle generates Java transport classes from these sources. Contract tests verify representative raw/analysis serialization round trips, dead-letter metadata round trips, and tolerance of unknown additive fields. Collection publishes `RawItemDiscovered` with an additive effective Analysis-settings snapshot; Analysis consumes that immutable snapshot and publishes `ItemAnalyzed` or `ItemRejected`. Legacy raw events without the snapshot remain decodable. Analysis, Results, and Event Observation publish `DeadLetterEvent` when deterministic poison input or bounded retry exhaustion reaches terminal handling.

## Read next

- [`AGENTS.md`](AGENTS.md)
- [`../../docs/specs/active/subspecs/backend-event-contracts.md`](../../docs/specs/active/subspecs/backend-event-contracts.md)

## Event Explorer decoding

`modules:event-observation` consumes the published raw/analyzed/rejected contracts through an independent Kafka group and maps selected fields into browser-facing JSON diagnostics. Generated Protobuf classes remain confined to the Kafka adapter boundary; REST/SSE do not expose them directly.
