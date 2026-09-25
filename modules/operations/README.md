---
type: Module Guide
title: Operations module
description: Current implementation of the operational change journal, Health Engine, and assisted-investigation boundary.
---
# Operations module

See [`contract.md`](contract.md) for authoritative ownership and dependency rules.

The module currently provides the accepted foundation and Health Engine plus verification-pending assisted-investigation Stage 3:

- durable sanitized operational change records;
- transactional journaling hooks used by Source, Monitoring Profile, and security-administration mutations;
- audit-safe successful DLQ replay markers plus typed deployment/test-scenario markers;
- persisted versioned Health Snapshots with count-bounded retention;
- bounded latest Health Report JSON/Markdown views;
- nearest before/after snapshot lookup plus numeric score/signal deltas around one recorded change;
- versioned configurable hard-rule scoring plus rolling median/MAD anomaly detection;
- bounded cluster-wide Prometheus evidence with local Analysis-outbox gauge fallback;
- multi-replica-safe periodic snapshot sampling coordinated through a PostgreSQL advisory lock;
- bounded sanitized `health-analysis-v1` packages for manual model use;
- persisted validated structured Incident Assessments with bounded retention;
- manual assessment import plus explicit provider-neutral invocation through `IncidentAnalyst`;
- an optional JDK-HTTP OpenAI-compatible adapter for local or external endpoints, disabled by default.

If Prometheus or required signals are unavailable, the engine records explicit uncertainty and may return `UNKNOWN` rather than treating missing telemetry as healthy. Assisted investigation never overrides that deterministic health state. Provider invocation is explicit only in this stage, model output is schema/evidence-reference validated before persistence, and provider failures do not stop the business pipeline.

## Read next

- [`contract.md`](contract.md)
- [`../../docs/specs/active/subspecs/backend-observability-intelligence.md`](../../docs/specs/active/subspecs/backend-observability-intelligence.md)
- [`../../docs/IMPLEMENTATION.md`](../../docs/IMPLEMENTATION.md)
