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

Analysis outbox pre-publication lease renewal is accepted after focused Analysis verification and the canonical repository gate passed. Claimed rows renew exact-token ownership immediately before Kafka send so sequential batch queueing cannot consume a later row's ownership window.

Kafka offset-commit failure separation is accepted after the corrected Micronaut `SYNC_PER_RECORD` implementation passed focused listener verification, `HttpPipelineSmokeIntegrationTest`, and the canonical repository gate. Analysis, Results, and Event Observation keep application retry/DLQ handling inside their listeners while framework-owned per-record commit remains outside those application failure domains.

Scheduler expired-lease fencing is accepted after the developer confirmed all relevant tests and validations passed. Renewal and completion now require both the exact token and a still-live persisted lease, so a stalled former owner cannot resurrect or consume overdue scheduled work after `lease_until`.

Kafka listener interruption fencing is accepted after the developer confirmed all relevant tests and validations passed. Interrupted Analysis, Results, and Event Observation work now escapes before application retry exhaustion or terminal DLQ classification, including zero-backoff paths.

Kafka failed-poll rewind is accepted after the developer confirmed all relevant tests and validations passed. The remaining Kafka consumer lifecycle review found no additional material correctness defect in shutdown/rebalance or `max.poll.interval` behavior, so Review I is closed without further consumer tuning. Analysis outbox interruption fencing is also accepted after developer verification: lifecycle cancellation now stops the current claimed batch before ordinary publication-failure classification.

Shared demand-driven polling lifecycle refactoring is accepted after developer verification. Results and Event Observation now reuse the framework-neutral `common.concurrent.DemandDrivenPollingLoop` for demand, delayed scheduling, and cancellation while retaining module-owned SSE/cursor semantics. Collection Run interruption recovery is also accepted after developer verification: publication interruption aborts run-level work and an interrupted started schedule leaves recovery to lease expiry rather than advancing `next_due_at`.
Source-fetch worker interruption propagation is accepted after developer verification. Worker-local virtual-thread cancellation now reaches Collection run-level coordination and existing scheduler recovery semantics. The remaining background-worker/executor lifecycle review found no additional material defect in executor rejection/saturation or scheduled-task cleanup, so Review II is closed.

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

1. Verify and accept `backend-analysis-outbox-expired-lease-fencing`: pre-publication renewal must reject an already-expired lease even when the old token is still persisted and no successor has claimed the row yet.
2. Continue the remaining durable-ownership/crash-window review across Analysis post-ack publication markers, acknowledged DLQ publication before source-offset commit, controlled replay/idempotency, and scheduled Collection Run completion; do not change production code without another concrete defect.
3. If no further material backend defect is found, keep the verified backend/OpenAPI baseline stable and continue with the next concrete product or companion-frontend requirement.

Companion-frontend roadmap state is owned by `signalharvester-web` and is not duplicated here.

## Deferred until justified

- independently deployed backend microservices;
- gRPC service boundaries;
- Schema Registry;
- LLM/embedding analysis as a required core dependency;
- automatic/bulk DLQ replay and replay UI/workflows;
- KEDA-driven autoscaling;
- richer scheduling, fuzzy/relevance-ranked search, and other browsing refinements that are not yet required by an active product slice.
