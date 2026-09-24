---
type: Specification
title: Capacity telemetry expansion
description: Production-safe Analysis outbox capacity telemetry selected from the measured local replica comparison before broader stress testing.
document_role: subspec
spec_status: verification-pending
parent: ../spec-signal-harvester-platform.md
---
# Capacity telemetry expansion

## Status

Implementation is in progress and verification is pending.

The preceding `backend-capacity-observability-baseline` measurement slice completed its one-replica and one-versus-three live workloads on 2026-09-24. It remains separately verification-pending until the canonical repository gate is recorded.

## Feature scope

- `OBSERVABILITY.APPLICATION`
- `OBSERVABILITY.INFRASTRUCTURE`
- `ANALYSIS.OUTBOX`
- `SCALABILITY.KAFKA_CONSUMERS`
- `TESTING.DETERMINISTIC_LOCAL`

## Goal

Make the Analysis transactional outbox capacity behavior explainable before introducing ramp, spike, soak, autoscaling, or throughput optimizations.

The telemetry must distinguish queue growth, batch processing, Kafka publication latency, and short PostgreSQL operation latency without changing the outbox ownership/fencing model.

## Measurement evidence

The first controlled one-versus-three replica comparison used the same 1,200-item workload in both runs. Three replicas reduced the observed end-to-end pipeline completion time and post-Collection drain time, while the observed Results lag-drain timing did not improve monotonically.

Those values are environment-specific evidence, not SLOs. The mixed downstream timing is sufficient to justify outbox-focused telemetry before broader stress testing or optimization.

## Requirements

### CT1 — backlog gauges

Application metrics must expose the current unpublished Analysis outbox row count and age of the oldest unpublished row.

Backlog sampling must use a bounded periodic read and must not participate in dispatcher ownership or publication transactions. Sampling failure must not fail or delay a publication outcome beyond the sampling work itself.

Because the database backlog is global while every backend replica exposes its own Prometheus endpoint, every replica may report the same sampled value. Infrastructure views must aggregate these gauges with `max`, not `sum`.

### CT2 — dispatcher work metrics

The existing outbox dispatcher must record low-cardinality metrics for:

- non-empty claimed batch size;
- non-empty batch processing duration;
- Kafka publication latency with bounded `success` / `failed` outcomes;
- short database-operation latency for `claim`, `renew_lease`, `mark_published`, and `mark_failed` with bounded `success` / `failed` outcomes.

Telemetry must not include event IDs, profile IDs, source IDs, raw URLs, exception messages, or other unbounded labels.

### CT3 — observability-only failure semantics

Backlog-sampling failures must be logged and measured but must not stop the Analysis outbox dispatcher.

Dispatcher instrumentation must preserve existing interruption, lease-renewal, publication-failure, retry, and exact-token fencing semantics. Metrics recording must not introduce a database transaction around Kafka I/O.

### CT4 — Grafana capacity views

The repository-owned operational dashboard must expose Analysis outbox backlog and dispatcher latency/batch signals using Prometheus.

Global backlog gauges must use replica-safe aggregation. Timer/summary panels may aggregate samples across replicas because those metrics represent local work performed by each replica.

### CT5 — measurement report high-water marks

The deterministic capacity report must summarize high-water marks already visible in its periodic samples for Analysis lag, Results lag, Event Observation lag, and pending outbox rows.

These values remain observations only and must not introduce pass/fail performance thresholds.

## Non-goals

This slice does not:

- change Analysis outbox batch size, poll interval, lease duration, retry behavior, or transaction boundaries;
- add backlog-drain loops or outbox cleanup/retention;
- add HPA/KEDA;
- add scheduler or SSE capacity metrics;
- implement ramp, spike, stress, or soak traffic;
- implement ML/anomaly detection;
- define performance budgets or production SLOs.

## Validation

Focused deterministic validation:

```bash
./infra/kubernetes/run_tests.sh
./gradlew :modules:analysis:test --no-watch-fs
./gradlew :modules:analysis:integrationTest --no-watch-fs
```

Live verification on the local Kubernetes stack must expose the new metrics from `/prometheus` and show populated outbox panels while a capacity workload is running.

Before acceptance, run:

```bash
./run_checks.sh
```

## Implementation tasks

- [x] expose pending-row and oldest-pending-age gauges from a separate Analysis outbox sampler;
- [x] record bounded batch, Kafka publication, and database-operation metrics in the dispatcher;
- [x] keep sampler failure independent from dispatcher correctness;
- [x] add Grafana panels with replica-safe backlog aggregation;
- [x] add capacity-report high-water marks;
- [x] add deterministic/unit/integration coverage for metric and backlog-query semantics;
- [ ] verify the metrics and panels during a live Kubernetes capacity run;
- [ ] run the canonical repository gate successfully;
- [ ] accept/archive this spec only after live telemetry evidence and canonical verification pass.
