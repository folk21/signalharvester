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

Profile-owned typed Analysis settings are accepted after the canonical repository gate passed. Monitoring Profiles are now authoritative for deterministic keyword behavior and carry the effective settings through `RawItemDiscovered`.

Production-oriented Results browsing and controlled owner-specific dead-letter recovery are accepted after their canonical repository gates passed. The repository-wide Jdbi persistence refactoring is also accepted after the final Results slice and Security handle-lifecycle correction passed the canonical repository gate. The scheduler pre-run lease recovery identified by the subsequent backend closure review is accepted as well: if local dispatch or heartbeat setup fails before a collection run starts, the exact-token lease is released without advancing its due time.

The current bounded reliability focus is verification-pending Analysis outbox lease renewal. Claimed batches are published sequentially, so later rows now renew exact-token ownership immediately before Kafka send instead of relying only on the original batch-claim expiry.

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

1. Continue the systematic backend lifecycle/reliability review across remaining Kafka consumer commit/shutdown/retry paths, background workers, executor rejection paths, durable ownership transitions, and resource cleanup.
2. Create a bounded specification only when the review identifies a material change whose intended semantics need explicit ownership and acceptance criteria.
3. If that review finds no further material defects, freeze a fresh verified backend/OpenAPI baseline and return to frontend work.
4. Consider additional scheduling refinements or KEDA only when a concrete product/operational requirement justifies them; manual horizontal scaling is already accepted.

`PRESENTATION.VIEWER_RESULTS` remains the next major frontend product slice after backend security. Detailed layout, routing, and frontend behavior belong to `signalharvester-web`.

## Deferred until justified

- independently deployed backend microservices;
- gRPC service boundaries;
- Schema Registry;
- LLM/embedding analysis as a required core dependency;
- automatic/bulk DLQ replay and replay UI/workflows;
- KEDA-driven autoscaling;
- richer scheduling, fuzzy/relevance-ranked search, and other browsing refinements that are not yet required by an active product slice.
