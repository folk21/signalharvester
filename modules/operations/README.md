---
type: Module Guide
title: Operations module
description: Current implementation of the operational change journal and deterministic/statistical Health Engine.
---
# Operations module

See [`contract.md`](contract.md) for authoritative ownership and dependency rules.

The module currently provides the accepted foundation plus the verification-pending deterministic/statistical Health Engine:

- durable sanitized operational change records;
- transactional journaling hooks used by Source, Monitoring Profile, and security-administration mutations;
- audit-safe successful DLQ replay markers plus typed deployment/test-scenario markers;
- persisted versioned Health Snapshots with count-bounded retention;
- bounded latest Health Report JSON/Markdown views;
- nearest before/after snapshot lookup plus numeric score/signal deltas around one recorded change;
- versioned configurable hard-rule scoring plus rolling median/MAD anomaly detection;
- bounded cluster-wide Prometheus evidence with local Analysis-outbox gauge fallback;
- multi-replica-safe periodic snapshot sampling coordinated through a PostgreSQL advisory lock.

If Prometheus or required signals are unavailable, the engine records explicit uncertainty and may return `UNKNOWN` rather than treating missing telemetry as healthy. Health analysis remains auxiliary and failures do not stop the business pipeline.

## Read next

- [`contract.md`](contract.md)
- [`../../docs/specs/active/subspecs/backend-observability-intelligence.md`](../../docs/specs/active/subspecs/backend-observability-intelligence.md)
- [`../../docs/IMPLEMENTATION.md`](../../docs/IMPLEMENTATION.md)
