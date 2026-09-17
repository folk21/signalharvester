---
type: Specification
title: Backend Kafka consumer horizontal scaling
description: Demonstrate safe multi-replica Kafka worker scaling and backlog drain within the modular-monolith Kubernetes deployment.
document_role: subspec
spec_status: verification-pending
parent: ../spec-signal-harvester-platform.md
---
# Backend Kafka consumer horizontal scaling

## Status

Implementation complete. Developer execution of the live Kubernetes scaling acceptance is pending.

## Feature scope

- `SCALABILITY.KAFKA_CONSUMERS`
- `RELIABILITY.IDEMPOTENCY`
- `DEPLOYMENT.KUBERNETES`
- `OBSERVABILITY.INFRASTRUCTURE`

## Goal

Demonstrate umbrella R26 and scenario S9 without splitting the modular monolith or introducing autoscaling infrastructure.

The current backend already creates one Analysis, Results, and Event Observation Kafka consumer instance per application replica. The Kubernetes topics have three partitions, so a deployment scaled from one to three backend replicas can increase Kafka worker parallelism while partitioning permits it. This slice makes that behavior explicit, reproducible, and observable.

## Requirements

### S1 — shared consumer groups remain the scaling boundary

Analysis, Results, and Event Observation replicas must keep stable shared consumer-group identities. Replica-specific consumer groups are forbidden because they would broadcast every event to every backend replica instead of sharing work.

No Java module is extracted into a separate deployment for this stage. Scaling operates on the existing `signalharvester-backend` Deployment.

### S2 — partition capacity is explicit

The local Kubernetes application topics used by the worker pipeline must have at least three partitions. The live scaling demonstration uses three backend replicas because useful consumer parallelism is bounded by available partitions.

Increasing backend replicas beyond a subscribed topic's partition count must not be described as increasing that consumer group's processing parallelism.

### S3 — deterministic backlog generation

The acceptance harness must create a large bounded set of unique RSS items through the normal Source and Collection Run APIs while Analysis consumption is temporarily disabled.

The workload must come from the repository-owned in-cluster deterministic fixture. Public internet sources and malformed Kafka payloads are not allowed for the scaling demonstration.

### S4 — scale from one to three workers while lag exists

The live workflow must:

1. reduce the backend Deployment to one replica;
2. generate positive Analysis consumer lag;
3. restore Analysis with one consumer and confirm lag still exists;
4. increase the backend Deployment to three replicas while the backlog remains positive;
5. confirm the Analysis consumer group reaches three distinct active clients and all three raw-event partitions are assigned;
6. confirm Results and Event Observation consumer groups also reach three active clients;
7. observe the Analysis backlog decrease and eventually drain to zero without manual offset changes.

### S5 — scaling preserves processing semantics

The scale-up workflow must not use a different application contract or bypass normal persistence.

After the backlog drains:

- the Analysis DLQ count must not increase;
- all generated unique items must exist in Analysis durable state;
- all generated relevant items must exist in the Results materialization;
- the Analysis outbox must return to its pre-workload pending-row baseline;
- normal authenticated HTTP availability must remain usable during the scaling operation.

### S6 — scaling remains observable

The existing Grafana/Prometheus workspace must continue to expose both backend replica availability and Kafka consumer lag so an operator can correlate replica changes with backlog behavior.

The acceptance runner must print the one-replica lag, scaled member/partition assignment, and final drained lag as direct runtime evidence.

## Failure semantics

- The runner must restore the original backend replica count and temporary environment overrides in `finally` cleanup.
- Temporary profiles must be deleted before their Sources.
- The test fixture must be removed after the run.
- A backlog that drains before the three-replica assignment can be observed is an inconclusive workload, not permission to weaken the assertions. Increase the bounded fixture workload instead.
- No scenario may commit or seek Kafka consumer offsets manually.

## Non-goals

This slice does not:

- add KEDA, HPA, or automatic lag-driven scaling;
- benchmark production throughput or establish a throughput SLA;
- create separately deployed Analysis, Results, or Event Observation services;
- change Kafka partition keys, event schemas, retry/DLQ semantics, or database ownership;
- claim useful consumer parallelism beyond topic partition capacity.

## Validation

Acceptance requires:

1. `./infra/kubernetes/run_tests.sh` passes, including deterministic scaling-runner/parser checks;
2. `./run_checks.sh` passes;
3. `./infra/kubernetes/verify-local.sh` passes;
4. `python3 infra/kubernetes/scaling/run_acceptance.py` passes with the default three-replica target;
5. the runner restores the original Deployment replica count and environment;
6. no public HTTP/Kafka contract or production Java code changes solely to support the scaling harness.

## Implementation tasks

1. Extend the existing opt-in in-cluster fixture with a bounded generated RSS workload for scaling tests.
2. Add a standard-library Python live scaling acceptance runner using public REST plus operator-level `kubectl`/`rpk` inspection.
3. Add deterministic parser/asset tests and keep them in `./infra/kubernetes/run_tests.sh`.
4. Document partition-limited parallelism, the live scaling workflow, and restoration behavior.
5. After developer acceptance, move this sub-spec to the archive and update current-state documentation.
