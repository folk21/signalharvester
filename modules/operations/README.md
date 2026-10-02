---
type: Module Guide
title: Operations module
description: Current implementation of the operational change journal, Health Engine, and assisted-investigation boundary.
---
# Operations module

See [`contract.md`](contract.md) for authoritative ownership and dependency rules.

The module currently provides the accepted foundation, Health Engine, Stage 3 manual/provider-neutral assisted investigation, Stage 4a read-only agentic investigation, accepted Stage 4b automatic trigger coordination, and verification-pending Stage 6 alert integration:

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
- an optional JDK-HTTP OpenAI-compatible adapter for local or external endpoints, disabled by default;
- bounded application-owned read-only investigation tools for Health context, allowlisted Prometheus queries, Loki log patterns, Tempo trace search/retrieval, change history, and capacity markers;
- strict per-investigation limits for model rounds, tool calls, wall-clock duration, telemetry lookback, result size, log rows, and trace search results;
- telemetry/tool-result redaction plus evidence-reference propagation into the final structured Incident Assessment;
- optional multi-replica-safe automatic event/periodic investigation triggers with durable lease, cooldown, deduplication, retry, and exhaustion state;
- durable application-owned human-attention alerts derived only from deterministic Health Snapshot history, with persistence/recovery thresholds, reopen cooldown, advisory assessment attachment, and bounded retention.

If Prometheus or required signals are unavailable, the engine records explicit uncertainty and may return `UNKNOWN` rather than treating missing telemetry as healthy. Assisted investigation never overrides that deterministic health state. Stage 4a explicit agentic invocation is accepted. Stage 4b optionally enables automatic event/periodic invocation, but it is `off` by default and uses durable PostgreSQL coordination. Model output is schema/evidence-reference validated before persistence, tool results are marked untrusted and sanitized, arbitrary PromQL/LogQL/SQL/shell/HTTP tools are not exposed, and provider/tool failures do not stop the business pipeline. Exact trigger leases prevent concurrent replica ownership, but the external provider boundary remains at-least-once across process crashes; a bounded retry may repeat a provider call after a crash before durable completion. Setting the provider to `off` keeps automatic workers idle without consuming persisted trigger attempts. Automatic trigger state and durable human-attention alert decisions are inspectable through the ADMIN Operations API. Structured model output may enrich an already-open alert but cannot open, escalate, resolve, or otherwise authorize one. External notification channels remain out of this slice.

## Read next

- [`contract.md`](contract.md)
- [`../../docs/specs/active/subspecs/backend-observability-intelligence.md`](../../docs/specs/active/subspecs/backend-observability-intelligence.md)
- [`../../docs/IMPLEMENTATION.md`](../../docs/IMPLEMENTATION.md)
