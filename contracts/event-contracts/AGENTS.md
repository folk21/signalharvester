---
type: Development Guide
title: Event contract development rules
description: Protobuf schema compatibility, generated-code, versioning, testing, and Kafka event-contract rules.
---
# Event contract development rules

## Compatibility boundary

`.proto` files under `src/main/proto/` are the source of truth for Kafka integration-event payloads.

Published schemas are compatibility-sensitive:

- never reuse removed field numbers for new meanings;
- reserve removed field numbers/names;
- prefer additive compatible evolution;
- keep explicit versioned packages;
- do not silently change field semantics;
- add compatibility/serialization tests for meaningful evolution.

## Generated code

Generated Java sources are Gradle build output. Do not edit or commit them as authoritative source, and do not use them as the owning module's domain model by default.

## Event design

Keep event IDs, correlation, trace/provenance metadata, and replay/idempotency needs explicit. Prefer typed messages over opaque property bags or `Any`-based designs.

## Testing

Contract tests should cover Protobuf generation and serialization. Kafka round-trip behavior belongs in integration tests with Testcontainers once producers/consumers exist.
