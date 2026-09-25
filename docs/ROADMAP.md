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

Profile-owned typed Analysis settings remain accepted. A bounded follow-up is verification-pending on 2026-09-22: profiles without keyword filtering now use an explicit all-relevant state instead of materializing deployment keyword defaults on create. Legacy persisted rows and raw events still use compatibility defaults.

Profile-owned typed Analysis settings are accepted after the canonical repository gate passed. Monitoring Profiles are now authoritative for deterministic keyword behavior and carry the effective settings through `RawItemDiscovered`.

Production-oriented Results browsing and controlled owner-specific dead-letter recovery are accepted after their canonical repository gates passed. The repository-wide Jdbi persistence refactoring is also accepted after the final Results slice and Security handle-lifecycle correction passed the canonical repository gate. The scheduler pre-run lease recovery identified by the subsequent backend closure review is accepted as well: if local dispatch or heartbeat setup fails before a collection run starts, the exact-token lease is released without advancing its due time.

Analysis outbox pre-publication lease renewal is accepted after focused Analysis verification and the canonical repository gate passed. Claimed rows renew exact-token ownership immediately before Kafka send so sequential batch queueing cannot consume a later row's ownership window.

Kafka offset-commit failure separation is accepted after the corrected Micronaut `SYNC_PER_RECORD` implementation passed focused listener verification, `HttpPipelineSmokeIntegrationTest`, and the canonical repository gate. Analysis, Results, and Event Observation keep application retry/DLQ handling inside their listeners while framework-owned per-record commit remains outside those application failure domains.

Scheduler expired-lease fencing is accepted after the developer confirmed all relevant tests and validations passed. Renewal and completion now require both the exact token and a still-live persisted lease, so a stalled former owner cannot resurrect or consume overdue scheduled work after `lease_until`.

Kafka listener interruption fencing is accepted after the developer confirmed all relevant tests and validations passed. Interrupted Analysis, Results, and Event Observation work now escapes before application retry exhaustion or terminal DLQ classification, including zero-backoff paths.

Kafka failed-poll rewind is accepted after the developer confirmed all relevant tests and validations passed. The remaining Kafka consumer lifecycle review found no additional material correctness defect in shutdown/rebalance or `max.poll.interval` behavior, so Review I is closed without further consumer tuning. Analysis outbox interruption fencing is also accepted after developer verification: lifecycle cancellation now stops the current claimed batch before ordinary publication-failure classification.

Shared demand-driven polling lifecycle refactoring is accepted after developer verification. Results and Event Observation now reuse the framework-neutral `common.concurrent.DemandDrivenPollingLoop` for demand, delayed scheduling, and cancellation while retaining module-owned SSE/cursor semantics. Collection Run interruption recovery is also accepted after developer verification: publication interruption aborts run-level work and an interrupted started schedule leaves recovery to lease expiry rather than advancing `next_due_at`.
Source-fetch worker interruption propagation is accepted after developer verification. Worker-local virtual-thread cancellation now reaches Collection run-level coordination and existing scheduler recovery semantics. The remaining background-worker/executor lifecycle review found no additional material defect in executor rejection/saturation or scheduled-task cleanup, so Review II is closed.
Analysis outbox expired-lease fencing is accepted after developer verification. Pre-publication renewal now requires both the exact token and a still-live persisted lease, so an expired former owner cannot resurrect ownership before Kafka send.
Analysis outbox failure-state lease fencing is accepted after developer verification. Publication-failure retry metadata now requires a still-live exact-token lease, so an unsuccessful send that outlives ownership cannot postpone immediate reclaim. The remaining durable-ownership/crash-window review found no additional material correctness defect across post-ack publication markers, DLQ acknowledgement before framework offset commit, controlled replay/idempotency, and scheduled Collection completion; Review III is closed.

The latest capacity-telemetry implementation remains verification-pending, while the operational-intelligence foundation, deterministic/statistical Health Engine, and Stage 3 manual/provider-neutral assisted investigation are accepted. Stage 4a read-only agentic investigation is implemented and verification-pending: explicit provider invocation can use bounded sanitized Health/Prometheus/Loki/Tempo/change/capacity tools under application-owned budgets. The next priority is a multi-replica-safe event/periodic trigger lease/cooldown boundary, followed by alert policy. The preceding capacity telemetry/baseline and earlier all-relevant Analysis follow-up remain separately verification-pending until canonical acceptance is recorded.

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

1. Operations foundation and deterministic/statistical Health Engine are accepted.
2. Stage 3 manual/provider-neutral assisted investigation is accepted.
3. Stage 4a bounded read-only Prometheus/Loki/Tempo/change-history/capacity tools and explicit agentic provider turns are implemented and verification-pending; the canonical gate remains offline/deterministic.
4. After Stage 4a acceptance, add a multi-replica-safe event/periodic trigger lease/cooldown policy, then application-owned alert policy. Continue ramp/spike/soak and Results REST/SSE load as both capacity work and operational-intelligence evaluation data; select throughput optimizations or custom ML/neural models only from repeated evidence.

Companion-frontend roadmap state is owned by `signalharvester-web` and is not duplicated here.

## Operational intelligence priority

This work is intentionally pulled forward because it improves day-to-day controllability of every later performance, reliability, and deployment stage.

The target path is:

1. telemetry + durable change journal;
2. persisted Health Snapshot/Report;
3. deterministic/statistical anomaly detection and change correlation;
4. manual LLM report analysis;
5. optional local/external LLM analyst with bounded read-only tools;
6. alert policy based on deterministic health plus optional structured model assessment;
7. custom ML/neural experiments only after labeled incident history demonstrates a need.

Repository-owned mutation tooling should emit change records/markers for behavior-affecting runtime changes. This is an application-level audit/correlation mechanism, not a Terraform-style infrastructure control plane.

## Deferred until justified

### Operational-capacity backlog

These are observed scaling/capacity risks, not current correctness defects. Measure them before changing the reliability model:

- Analysis outbox backlog observability: pending-row count, oldest-pending age, claimed batch size, batch duration, Kafka publish latency, and outbox database-operation latency;
- Analysis outbox retention/cleanup for old `published_at` rows so the outbox table and stored payload bytes do not grow without bound;
- bounded backlog draining for the Analysis outbox if the fixed scheduler delay becomes a measured throughput limiter; avoid an unbounded drain loop that can monopolize a scheduled worker;
- Analysis outbox transaction-volume optimization only if measurements justify it; preserve short transactions around ownership fencing and durable outcomes and do not hold a PostgreSQL transaction across Kafka I/O;
- conditional pre-publication lease renewal only if it can retain the existing exact-token/live-lease fencing semantics; do not remove per-row ownership proof merely to save a database round trip;
- set-oriented Collection scheduler claiming if per-enabled-profile schedule transactions become a measured scaling bottleneck;
- shared/fan-out SSE polling if per-subscriber PostgreSQL polling becomes a measured viewer-scaling bottleneck;
- batch Source-existence validation for Monitoring Profiles if large source memberships make per-source lookup transactions material.

Other deferred architecture/product work:

- independently deployed backend microservices;
- gRPC service boundaries;
- Schema Registry;
- LLM/embedding analysis as a required core product-analysis dependency; operational LLM investigation remains optional under `OBSERVABILITY.ASSISTED_INVESTIGATION`;
- automatic/bulk DLQ replay and replay UI/workflows;
- KEDA-driven autoscaling;
- richer scheduling, fuzzy/relevance-ranked search, and other browsing refinements that are not yet required by an active product slice.
