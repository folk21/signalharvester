---
type: Roadmap
title: Roadmap
description: Compact SignalHarvester backend roadmap and current implementation direction.
---
# Roadmap

## Current position

The main backend pipeline is implemented and verified through `ANALYSIS.OUTBOX`.

The following platform work is also accepted:

- `RELIABILITY.KAFKA_RETRY`;
- `RELIABILITY.DEAD_LETTER`;
- current `RELIABILITY.IDEMPOTENCY` protections;
- `OBSERVABILITY.APPLICATION`;
- `SECURITY.IDENTITY_ROLES`;
- `SECURITY.AUTHENTICATION`;
- `SECURITY.AUTHORIZATION`;
- `SECURITY.EXTERNAL_SOURCE_ACCESS`;
- backend-owned `DEPLOYMENT.KUBERNETES`;
- `OBSERVABILITY.INFRASTRUCTURE`;
- live system resilience acceptance;
- `SCALABILITY.KAFKA_CONSUMERS` with live one-to-three replica backlog-drain acceptance.

No new bounded backend implementation sub-spec is active yet.

Full platform Kubernetes acceptance still requires a real frontend image from `signalharvester-web`.

Stable feature IDs are defined in [`FEATURES.md`](FEATURES.md).

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
- `DIAGNOSTICS.ANALYSIS_INSPECTION`;
- `RESULTS.MATERIALIZATION`, `RESULTS.BROWSING`, and `RESULTS.LIVE`;
- `DIAGNOSTICS.EVENT_OBSERVATION` and `DIAGNOSTICS.PROCESSING_FLOW`.

## P2 — reliability, observability, security, and deployment

Accepted reliability/security work:

- `RELIABILITY.KAFKA_RETRY` and `RELIABILITY.DEAD_LETTER`;
- `ANALYSIS.OUTBOX`;
- `OBSERVABILITY.APPLICATION`;
- `SECURITY.IDENTITY_ROLES`, `SECURITY.AUTHENTICATION`, and `SECURITY.AUTHORIZATION`;
- `SECURITY.EXTERNAL_SOURCE_ACCESS`.

Accepted deployment/system work:

- backend-owned `DEPLOYMENT.KUBERNETES`;
- `OBSERVABILITY.INFRASTRUCTURE`;
- controlled live resilience acceptance for restart, persistence outage, retry/DLQ, lag recovery, outbox recovery, scheduler leases, authorization, and telemetry evidence.

Accepted scaling work:

- `SCALABILITY.KAFKA_CONSUMERS` — live scale from one to three backend replicas while Analysis lag exists, with three distinct Analysis group members owning the three raw-event partitions and final lag draining to zero.

## Next backend stages

1. Define the next bounded backend refinement. Profile-owned analysis settings are the leading candidate because they complete monitoring-profile ownership of deterministic analysis behavior.
2. Follow with another product refinement such as controlled replay operations, Results search/pagination, or scheduling refinement.
3. Consider KEDA only if an autoscaling policy is justified by operational needs; manual horizontal scaling is already accepted.

`PRESENTATION.VIEWER_RESULTS` remains the next major frontend product slice after backend security. Detailed layout, routing, and frontend behavior belong to `signalharvester-web`.

## Deferred until justified

- independently deployed backend microservices;
- gRPC service boundaries;
- Schema Registry;
- LLM/embedding analysis as a required core dependency;
- automatic DLQ replay UI/workflows;
- KEDA-driven autoscaling;
- richer scheduling and search capabilities that are not yet required by an active product slice.
