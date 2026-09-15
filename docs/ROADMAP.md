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

No implementation sub-spec is currently selected as `current_focus` while the documentation vocabulary/readability cleanup is applied. The next planned implementation stage is `OBSERVABILITY.APPLICATION`. The active authentication/authorization sub-spec follows it before production-style Kubernetes/system acceptance.

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
- `ANALYSIS.OUTBOX`.

Next:

1. `OBSERVABILITY.APPLICATION` — OpenTelemetry instrumentation plus health/readiness.
2. `SECURITY.IDENTITY_ROLES`, `SECURITY.AUTHENTICATION`, and `SECURITY.AUTHORIZATION` — stateless JWT authentication and backend-enforced RBAC.
3. `PRESENTATION.VIEWER_RESULTS` — frontend-owned consumer result experience after the backend security contract is accepted.

The VIEWER UI belongs to `signalharvester-web`; its detailed layout/routing behavior must be defined there when frontend development resumes.

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
