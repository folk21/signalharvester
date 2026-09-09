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
    results/v1/
```

`.proto` files are authoritative. Generated Java classes are build artifacts.

## Current state

The first `v1` schemas are implemented:

- `common/v1/event-envelope.proto`;
- `collection/v1/raw-item-discovered.proto`.

Gradle generates Java transport classes from these sources. Contract tests verify representative serialization round trips and tolerance of unknown additive fields.

## Read next

- [`AGENTS.md`](AGENTS.md)
- [`../../docs/specs/active/subspecs/backend-event-contracts.md`](../../docs/specs/active/subspecs/backend-event-contracts.md)
