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

The harness runs the normal live deployment preflight itself unless `--skip-preflight` is supplied. It also emits a versioned bounded operational scenario-evidence artifact for offline Health/alert/assisted-investigation evaluation.

Before that preflight, the runner verifies the PostgreSQL StatefulSet acceptance baseline. The normal topology is exactly one PostgreSQL replica. A leftover `replicas=0` state from an interrupted PostgreSQL-outage scenario is repaired back to one replica and waited ready; any other unexpected replica count is rejected instead of being silently rewritten.

## Run

From the repository root:

```bash
python3 infra/kubernetes/resilience/run_acceptance.py
```

The default namespace is `signalharvester`. Override it with `--namespace` or `SIGNALHARVESTER_K8S_NAMESPACE`.

To repair only the known PostgreSQL acceptance baseline after an interrupted run, without executing scenarios, use:

```bash
python3 infra/kubernetes/resilience/run_acceptance.py --repair-baseline-only
```

The harness uses local ports 18081, 18082, 19091, 13101, and 13201. Port 18081 is the normal backend Service tunnel; 18082 is a temporary pod-specific tunnel used to prove that an already-issued JWT works through a distinct surviving replica and again through the restarted replica. Prometheus, Loki, and Tempo use 19091, 13101, and 13201. Override the corresponding command-line options when those ports are already in use. Long-lived port-forwards are supervised and restarted if `kubectl port-forward` exits because a selected pod is replaced during a controlled restart/rollout. Each replacement is considered recovered only after that specific process reports a fresh `Forwarding from ...` readiness line, so an earlier process generation cannot satisfy the readiness check. The default evaluation artifact is `build/reports/operational-intelligence/resilience-scenario-evidence.json`; override it with `--evidence-output`.

## Scenario evidence

The runner labels the evaluation-relevant parts of the acceptance sequence as `NORMAL_OPERATION`, `POD_RESTART`, `SLOW_EXTERNAL_SOURCE`, `POSTGRESQL_OUTAGE`, `KAFKA_LAG`, `OUTBOX_BACKLOG`, and `KAFKA_BROKER_RESTART`. Each record separates harness-owned ground truth from observed evidence and may contain durable Operations scenario markers, explicit Health Snapshots, curated scalar measurements, selected bounded logs, and limitations. Fault scenarios record `FAULT_START` and `FAULT_END` Operations markers around the controlled fault window; scenarios such as a full PostgreSQL outage may still have no Health Snapshot inside that window because the authoritative snapshot store is intentionally unavailable. A failed labeled scenario is retained as partial evidence before the runner exits non-zero.

The evidence artifact is evaluation input only. It does not become application state, does not change Health status/score, and does not authorize alerts or remediation. See [`../evaluation/README.md`](../evaluation/README.md) for the shared format.

## Scenarios

The run exercises:

- VIEWER/ADMIN/anonymous authorization boundaries;
- an already-issued JWT through a distinct surviving backend replica during a real container restart and again through the restarted replica after recovery;
- deployment availability during a five-second source response delay;
- retryable Analysis failure during a controlled PostgreSQL outage, bounded retry exhaustion, Analysis DLQ publication, measured retry/terminal-log counts plus DLQ detection time, bounded application-level database recovery through an authenticated read, and PostgreSQL recovery;
- positive Kafka consumer lag while Analysis is disabled, followed by normal lag drain and Result recovery;
- durable Analysis outbox state while dispatch is disabled, followed by publication after rollout;
- exactly one scheduled run for one forced-due profile while two backend replicas are active;
- Redpanda pod/PVC restart, bounded recovery of the Analysis/Results/Event Observation consumer groups to stable application membership, one scenario-bounded post-recovery Collection mutation, and successful Kafka processing with measured recovery latency;
- backend restart metrics, Loki logs, Tempo traces, and PostgreSQL/JDBC span metrics.

## Deterministic fixture and outbound access

`fixture.yaml` is not referenced by the root Kustomize target. The acceptance runner applies it only for the live test.

The regular `security` environment blocks Kubernetes-private source addresses. The runner reads the fixture Service ClusterIP and temporarily adds only that `/32` or `/128` address to `SIGNALHARVESTER_COLLECTION_OUTBOUND_ALLOWED_CIDRS`. It also extends the local JWT/cookie lifetime for the duration of the long-running acceptance sequence so credentials do not expire between failure scenarios. After the authenticated Service tunnel is established, every in-scenario backend environment rollout waits for the existing read-only `GET /api/v1/sources` path to return HTTP 200 before any subsequent mutation is attempted. This closes the short race where Kubernetes reports the replacement Deployment ready while the long-lived Service port-forward is still reconnecting to a new pod. The original Deployment environment is restored during cleanup.

## Fault injection boundaries

The harness intentionally uses operator-level access that normal application clients do not have:

- PostgreSQL StatefulSet scaling is used to exercise retryable consumer failure;
- the acceptance Deployment explicitly bounds Hikari acquisition plus PostgreSQL JDBC connect/socket waits, and pins the expected three Analysis attempts with a 250 ms retry backoff so retry exhaustion fits inside the scenario budget;
- a previously emitted valid raw Kafka record is replayed with `rpk` while PostgreSQL is unavailable;
- the Analysis outbox table is inspected only to prove durable pending/recovery state;
- one collection schedule row has `next_due_at` forced to `now()` so scheduler-lease acceptance does not wait a full minute.

These operations are acceptance instrumentation. They do not establish new application APIs or cross-module production dependencies. The application also exposes the same database wait bounds as normal deployment configuration (`SIGNALHARVESTER_DB_CONNECTION_TIMEOUT_MS`, `SIGNALHARVESTER_DB_CONNECT_TIMEOUT_SECONDS`, and `SIGNALHARVESTER_DB_SOCKET_TIMEOUT_SECONDS`); the Kubernetes target chooses shorter local acceptance values than the conservative application defaults. After the PostgreSQL StatefulSet becomes ready again, the harness also polls the existing authenticated `GET /api/v1/sources` boundary until it returns HTTP 200 before issuing any post-recovery mutation. This proves application-level JDBC recovery without adding a fixed sleep or restarting the backend, and records the elapsed `databaseRecoveryMs` in scenario evidence. After a Redpanda restart, Kubernetes broker readiness is followed by a read-only `rpk group describe` barrier for the Analysis, Results, and Event Observation consumer groups. The harness requires each group to be `Stable`, to have at least the current backend replica count represented in membership, and to expose active partition assignments before it performs exactly one post-broker Collection mutation. That acknowledged request uses the scenario timeout rather than the ordinary short HTTP timeout and is never retried; evidence records `kafkaApplicationRecoveryMs`, `postBrokerCollectionMs`, consumer membership, and Analysis lag after recovery.

## Cleanup

On normal completion or failure the runner attempts to:

- restore the PostgreSQL StatefulSet to the one-replica acceptance baseline and wait for it to become ready;
- delete temporary Monitoring Profiles before Sources;
- disable the temporary VIEWER identity;
- restore backend Deployment environment overrides;
- remove the resilience fixture Deployment/Service/ConfigMap;
- stop all port-forward processes.

The same PostgreSQL repair is also executed before every normal resilience run. This makes a later run recover the specific durable `replicas=0` leftover that can survive a host sleep, process interruption, or transient Kubernetes API outage. The runner does not attempt to rewrite an unexpected multi-replica PostgreSQL topology.

Collection/Analysis/Results history from the scenarios remains in the disposable local database by design. Delete the namespace when a completely clean environment is required.
