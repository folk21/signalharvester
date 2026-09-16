---
type: Infrastructure Guide
title: Kubernetes resilience acceptance
description: Controlled live failure and recovery scenarios for the multi-replica SignalHarvester local Kubernetes stack.
---
# Kubernetes resilience acceptance

This directory contains the opt-in live acceptance harness for the production-style local backend stack.

It does not run as part of routine `./run_checks.sh`. The harness intentionally restarts workloads, scales PostgreSQL temporarily, toggles backend feature activation through Deployment environment variables, produces controlled Kafka traffic, and performs test-only persistence state injection for scheduler acceptance.

## Prerequisites

Before running resilience acceptance:

1. build and load `signalharvester-backend:local` into the local cluster;
2. create the runtime Secrets;
3. apply `infra/kubernetes`;
4. make sure `./infra/kubernetes/verify-local.sh` can pass.

The harness runs the normal live deployment preflight itself unless `--skip-preflight` is supplied.

## Run

From the repository root:

```bash
python3 infra/kubernetes/resilience/run_acceptance.py
```

The default namespace is `signalharvester`. Override it with `--namespace` or `SIGNALHARVESTER_K8S_NAMESPACE`.

The harness uses local ports 18081, 19091, 13101, and 13201 for backend, Prometheus, Loki, and Tempo port-forwards. Override the corresponding command-line options when those ports are already in use.

## Scenarios

The run exercises:

- VIEWER/ADMIN/anonymous authorization boundaries;
- an already-issued JWT across a real backend container restart;
- deployment availability during a five-second source response delay;
- retryable Analysis failure during a controlled PostgreSQL outage, bounded retry exhaustion, Analysis DLQ publication, and PostgreSQL recovery;
- positive Kafka consumer lag while Analysis is disabled, followed by normal lag drain and Result recovery;
- durable Analysis outbox state while dispatch is disabled, followed by publication after rollout;
- exactly one scheduled run for one forced-due profile while two backend replicas are active;
- Redpanda pod/PVC restart followed by successful Kafka processing;
- backend restart metrics, Loki logs, Tempo traces, and PostgreSQL/JDBC span metrics.

## Deterministic fixture and outbound access

`fixture.yaml` is not referenced by the root Kustomize target. The acceptance runner applies it only for the live test.

The regular `security` environment blocks Kubernetes-private source addresses. The runner reads the fixture Service ClusterIP and temporarily adds only that `/32` or `/128` address to `SIGNALHARVESTER_COLLECTION_OUTBOUND_ALLOWED_CIDRS`. It also extends the local JWT/cookie lifetime for the duration of the long-running acceptance sequence so credentials do not expire between failure scenarios. The original Deployment environment is restored during cleanup.

## Fault injection boundaries

The harness intentionally uses operator-level access that normal application clients do not have:

- PostgreSQL StatefulSet scaling is used to exercise retryable consumer failure;
- a previously emitted valid raw Kafka record is replayed with `rpk` while PostgreSQL is unavailable;
- the Analysis outbox table is inspected only to prove durable pending/recovery state;
- one collection schedule row has `next_due_at` forced to `now()` so scheduler-lease acceptance does not wait a full minute.

These operations are acceptance instrumentation. They do not establish new application APIs or cross-module production dependencies.

## Cleanup

On normal completion or failure the runner attempts to:

- delete temporary Monitoring Profiles before Sources;
- disable the temporary VIEWER identity;
- restore backend Deployment environment overrides;
- remove the resilience fixture Deployment/Service/ConfigMap;
- stop all port-forward processes.

Collection/Analysis/Results history from the scenarios remains in the disposable local database by design. Delete the namespace when a completely clean environment is required.
