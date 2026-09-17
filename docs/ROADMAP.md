---
type: Roadmap
title: Roadmap
description: Compact SignalHarvester backend roadmap and current implementation direction.
---
# Roadmap

## Current position

The main functional backend pipeline is implemented and verified through `ANALYSIS.OUTBOX`.

Accepted P2 reliability work includes:

- `RELIABILITY.KAFKA_RETRY`;
- `RELIABILITY.DEAD_LETTER`;
- `RELIABILITY.IDEMPOTENCY` protections used by current consumers;
- `ANALYSIS.OUTBOX` for PostgreSQL/Kafka consistency in Analysis.

`OBSERVABILITY.APPLICATION`, backend authentication/authorization, `SECURITY.EXTERNAL_SOURCE_ACCESS`, the backend-owned Kubernetes/infrastructure-observability stack, and the live system resilience acceptance are accepted. The current bounded backend focus is `SCALABILITY.KAFKA_CONSUMERS`; full platform Kubernetes acceptance still requires a real frontend image from `signalharvester-web`.

Stable feature identifiers are defined in [`FEATURES.md`](FEATURES.md).

## P0 — repository and contract foundation

Accepted foundation:

- `PLATFORM.MODULAR_MONOLITH` — Gradle/Micronaut modular-monolith ownership;
- `CONTRACTS.HTTP` — REST/OpenAPI and SSE/JSON application boundaries;
- `CONTRACTS.KAFKA_PROTOBUF` — versioned Kafka/Protobuf integration contracts;
- PostgreSQL/Flyway module-local persistence ownership;
- deterministic unit/integration verification and repository quality gates.

## P1 — functional product pipeline

Accepted backend capabilities:

- `CONFIGURATION.SOURCES` and `CONFIGURATION.MONITORING_PROFILES`;
- `COLLECTION.SOURCE_TEST`, `COLLECTION.RUNS`, `COLLECTION.SCHEDULING`, and `COLLECTION.ADAPTERS`;
- `EVENTING.PIPELINE` and `EVENTING.CORRELATION`;
- `ANALYSIS.NORMALIZATION`, `ANALYSIS.DEDUPLICATION`, and deterministic `ANALYSIS.CLASSIFICATION`;
- `RESULTS.MATERIALIZATION`, `RESULTS.BROWSING`, and `RESULTS.LIVE`;
- `DIAGNOSTICS.EVENT_OBSERVATION` and `DIAGNOSTICS.PROCESSING_FLOW`.

## P2 — reliability, observability, and security

Accepted:

- `RELIABILITY.KAFKA_RETRY` and `RELIABILITY.DEAD_LETTER`;
- `ANALYSIS.OUTBOX`;
- `OBSERVABILITY.APPLICATION`;
- `SECURITY.IDENTITY_ROLES`, `SECURITY.AUTHENTICATION`, and `SECURITY.AUTHORIZATION`.

Accepted deployment/system milestone:

- backend-owned `DEPLOYMENT.KUBERNETES` + `OBSERVABILITY.INFRASTRUCTURE`;
- controlled live system resilience acceptance for restart, persistence outage, retry/DLQ, lag recovery, outbox recovery, scheduler leases, authorization, and telemetry evidence.

Current verification-pending:

- `SCALABILITY.KAFKA_CONSUMERS` — live scale from one to three backend replicas while Analysis lag exists, with shared-group partition ownership and semantic-completeness checks.

Next backend stages:

1. execute and accept Kafka consumer horizontal-scaling verification.
2. choose the next lower-priority product refinement from analysis settings, replay operations, search/pagination, scheduling refinement, or other active product need.
3. consider KEDA only after manual horizontal scaling behavior is accepted and an autoscaling policy is justified.

`PRESENTATION.VIEWER_RESULTS` remains the next major frontend product slice after backend security is accepted, but it is intentionally deferred until frontend development resumes. Its detailed layout/routing behavior belongs to `signalharvester-web`.

## P2 — deployment and system acceptance

After application observability and security:

- implement `DEPLOYMENT.KUBERNETES`;
- implement `OBSERVABILITY.INFRASTRUCTURE` with Prometheus/Loki/Tempo/Grafana-oriented views;
- run controlled restart, lag, slow-source, retry/DLQ, outbox-recovery, and authorization-boundary scenarios;
- complete one production-style local system acceptance pass.

That acceptance point is the intended logical milestone before lower-priority product refinements.

## Deferred until justified

- independently deployed backend microservices;
- gRPC service boundaries;
- Schema Registry;
- LLM/embedding analysis as a required core dependency;
- automatic DLQ replay UI/workflows;
- KEDA-driven autoscaling;
- richer scheduling and search capabilities that are not required for the system milestone.
