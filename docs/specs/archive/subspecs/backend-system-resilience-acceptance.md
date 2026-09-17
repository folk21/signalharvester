---
type: Specification
title: Backend system resilience acceptance
description: Exercise the deployed multi-replica backend under controlled restart, lag, persistence outage, retry/DLQ, outbox, scheduler, authorization, and observability scenarios.
document_role: subspec
spec_status: completed
parent: ../spec-signal-harvester-platform.md
---
# Backend system resilience acceptance

## Status

Accepted on 2026-09-17 after the developer executed the complete live Kubernetes resilience workflow successfully. The run also supplied the live runtime evidence required to accept the backend-owned Kubernetes/observability deployment sub-spec.

## Feature scope

- `DEPLOYMENT.KUBERNETES`
- `OBSERVABILITY.INFRASTRUCTURE`
- `RELIABILITY.KAFKA_RETRY`
- `RELIABILITY.DEAD_LETTER`
- `RELIABILITY.IDEMPOTENCY`
- `ANALYSIS.OUTBOX`
- `COLLECTION.SCHEDULING`
- `SECURITY.AUTHENTICATION`
- `SECURITY.AUTHORIZATION`
- `RUNTIME.CONCURRENCY`

No new long-lived capability is introduced by this specification. It is an acceptance slice over already implemented features.

## Goal

Demonstrate that the production-style local backend remains understandable and recoverable when important infrastructure or workload boundaries fail.

The scenarios must use the real Kubernetes deployment, PostgreSQL, Redpanda, backend HTTP/security filters, Kafka consumers/producers, Analysis outbox, scheduler leases, and observability stack. The harness must not add test-only HTTP endpoints to production application code.

## Requirements

### S1 — reproducible in-cluster source fixture

Acceptance must use a deterministic source fixture deployed inside the SignalHarvester namespace rather than a public website.

Because the accepted outbound-source policy runs in `SECURE` mode, the harness may temporarily allow only the fixture Service address through the existing CIDR override. The original backend environment must be restored after the run.

### S2 — stateless authentication across backend restart

With at least two backend replicas:

- create an authenticated `VIEWER` session;
- restart one backend container without changing JWT/CSRF secrets;
- keep the service available through the surviving replica;
- verify the already-issued VIEWER JWT remains accepted after the restarted container becomes ready;
- verify VIEWER remains denied from ADMIN user-management APIs and anonymous Results access remains denied.

### S3 — slow-source availability

A deterministic source must delay its HTTP response while one manual Collection Run is active.

During that delay the deployment readiness endpoint and an independent authenticated Results read must remain available. The slow run must eventually complete and materialize its Result.

### S4 — bounded retry and terminal DLQ under PostgreSQL outage

Acceptance must use a valid previously published `RawItemDiscovered` Kafka payload, not malformed bytes, so Analysis enters its retryable application-failure path.

While PostgreSQL is intentionally unavailable:

- replay the valid raw record;
- Analysis must exhaust the configured bounded retry count;
- the Analysis DLQ topic record count must advance;
- backend logs must show terminal dead-letter handling after the configured attempts;
- after PostgreSQL recovery a normal collection flow must succeed again.

### S5 — observable Kafka lag and recovery

Temporarily disable Analysis consumers across the backend deployment, publish multiple raw items through the normal Collection API, and verify Analysis consumer lag becomes positive.

After Analysis is restored:

- the group lag must drain to zero;
- all fixture items must materialize in Results;
- no manual offset manipulation is allowed.

### S6 — Analysis outbox durability across rollout

Temporarily disable only the Analysis outbox dispatcher while keeping Analysis consumption enabled.

The scenario must demonstrate:

- durable Analysis inspection state exists;
- pending outbox rows increase;
- Results do not appear while dispatch is disabled;
- after the dispatcher is restored through a backend rollout, Results materialize;
- pending outbox rows return to the pre-scenario baseline.

### S7 — multi-replica scheduler lease

With two backend replicas, create one enabled monitoring profile and force its persisted schedule state due as test-only infrastructure fault injection.

Exactly one Collection Run must execute for that due instant. The harness may inspect/update the collection-owned schedule table only for this acceptance injection; application production code must continue using the published scheduling boundary.

### S8 — Redpanda restart recovery

Restart the single-node local Redpanda pod while preserving its PVC. After the StatefulSet is ready again, a new normal Collection Run must publish through Kafka and materialize in Results without application reconfiguration.

### S9 — infrastructure telemetry proves the scenarios

After the scenarios complete:

- Kubernetes restart metrics must report the backend container restart;
- backend logs must be queryable in Loki;
- backend traces must be searchable in Tempo;
- PostgreSQL/JDBC span metrics must be queryable in Prometheus.

Observability failure remains auxiliary and must not be used as an application correctness dependency.

## Failure semantics

- The acceptance harness must restore temporary backend environment overrides even when a scenario fails.
- Temporary monitoring profiles must be deleted before their Sources.
- The temporary VIEWER identity must be disabled after the run because the current public administration API intentionally has no user-delete operation.
- The fixture workload must be deleted after the run.
- Failure output must identify the scenario and the observed runtime evidence rather than silently retrying forever.
- A failed scenario does not authorize weakening an already accepted module-level reliability test.

## Non-goals

This slice does not:

- introduce chaos-engineering frameworks;
- add application test endpoints;
- implement automated DLQ replay;
- claim exactly-once Kafka/PostgreSQL behavior;
- add KEDA or other autoscaling;
- benchmark production throughput or latency;
- complete the separate frontend portion of umbrella R24.

## Validation

Acceptance requires:

1. `./infra/kubernetes/run_tests.sh` passes, including deterministic resilience-harness asset/parser tests;
2. `./run_checks.sh` passes;
3. the backend/infrastructure cluster satisfies `./infra/kubernetes/verify-local.sh`;
4. `python3 infra/kubernetes/resilience/run_acceptance.py` passes all S1-S9 scenarios;
5. the harness leaves backend environment overrides restored and deletes its temporary fixture/profile/source resources;
6. no production Java code or public HTTP/Kafka contract is changed solely to support the acceptance harness.

After this live gate succeeds, the backend-owned Kubernetes/observability deployment sub-spec and this resilience sub-spec may be accepted in order. Full umbrella R24 still depends on the separately owned `signalharvester-web` image/workload path.

## Implementation tasks

1. Add an opt-in in-cluster deterministic RSS fixture with a bounded slow-response path.
2. Add a standard-library Python acceptance runner around public REST plus operator-level `kubectl`/`rpk`/PostgreSQL fault injection.
3. Add deterministic tests for fixture isolation, required scenario coverage, and `rpk` output parsing.
4. Document the live acceptance workflow and its cleanup/failure semantics.
5. Update active specification navigation and roadmap status without marking either live stage accepted before developer execution succeeds.
