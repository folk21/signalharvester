---
type: Infrastructure Guide
title: Local Kubernetes deployment
description: Backend, PostgreSQL, Redpanda, observability, secrets, image loading, and verification workflow for a production-style local SignalHarvester cluster.
---
# Local Kubernetes deployment

This directory owns the backend repository's local production-style Kubernetes stack.

It deploys:

- two replicas of the SignalHarvester modular-monolith backend;
- PostgreSQL 16;
- single-node Redpanda with explicit SignalHarvester topic provisioning;
- Prometheus;
- Loki;
- Tempo with span-metric generation;
- Grafana Alloy for Kubernetes log collection;
- kube-state-metrics;
- Grafana with provisioned data sources and the SignalHarvester Overview dashboard.

The frontend remains a separate deliverable. [`frontend/`](frontend/README.md) defines only the runtime image boundary expected from `signalharvester-web`.

## Requirements

- Docker or another compatible image builder;
- a local Kubernetes cluster such as `kind` or `k3d`;
- `kubectl` with Kustomize support;
- Python 3 for local secret generation and repository asset tests;
- enough local cluster capacity for PostgreSQL, Redpanda, two backend pods, and the observability stack.

The repository does not create the Kubernetes cluster itself. This keeps the manifests independent from one local distribution.

## Build the backend image

From the repository root:

```bash
docker build -f app/Dockerfile -t signalharvester-backend:local .
```

If the local cluster does not share the host Docker image store, load the image explicitly. Examples:

```bash
kind load docker-image signalharvester-backend:local
```

or:

```bash
k3d image import signalharvester-backend:local
```

## Create local secrets

The manifests contain no JWT, CSRF, bootstrap, database, or Grafana credentials. Create local Secret objects before applying the stack:

```bash
./infra/kubernetes/create-local-secrets.sh
```

The script generates random local values when environment variables are not supplied and prints newly created bootstrap ADMIN/Grafana credentials once. Existing Secret objects are left unchanged on later runs so a retained PostgreSQL volume cannot be broken by an implicit database-password rotation. To supply stable local values, export the same `SIGNALHARVESTER_*` variables used by the application plus optional `GRAFANA_ADMIN_USER` and `GRAFANA_ADMIN_PASSWORD` before the first run.

Do not commit generated credentials. Credential rotation is an explicit coordinated operation; for the disposable local stack, deleting/recreating the namespace is the simplest full reset.

## Deploy

```bash
kubectl apply -k infra/kubernetes
```

The backend runs with `MICRONAUT_ENVIRONMENTS=security`, so authentication/RBAC and the restrictive external-source destination policy are enabled. The local port-forward workflow intentionally sets the authentication cookie `Secure` flag to `false`; this local manifest is not a production Internet exposure configuration and does not define TLS/Ingress.

Wait for the cluster and run the live verification. The verifier prints each workload before waiting and emits focused pod/workload/event diagnostics if readiness times out:

```bash
./infra/kubernetes/verify-local.sh
```

After that baseline passes, run the controlled resilience acceptance:

```bash
python3 infra/kubernetes/resilience/run_acceptance.py
```

The resilience harness is opt-in because it intentionally injects failures and restarts. Read [`resilience/README.md`](resilience/README.md) before running it. After that workflow passes, `python3 infra/kubernetes/scaling/run_acceptance.py` demonstrates partition-bounded Kafka worker scaling; see [`scaling/README.md`](scaling/README.md).

For measurement-oriented capacity work, run `python3 infra/kubernetes/performance/run_baseline.py` after the normal local verification. It creates a bounded deterministic pipeline workload and writes environment-specific JSON evidence without enforcing a benchmark threshold. Use `python3 infra/kubernetes/performance/run_comparison.py` for a same-workload replica comparison with separate raw reports and a neutral comparison JSON; see [`performance/README.md`](performance/README.md).

## Access local services

Backend:

```bash
kubectl -n signalharvester port-forward service/signalharvester-backend 8080:8080
```

Grafana:

```bash
kubectl -n signalharvester port-forward service/grafana 3000:3000
```

Prometheus when direct query/debugging is useful:

```bash
kubectl -n signalharvester port-forward service/prometheus 9090:9090
```

The backend exports OTLP traces directly to Tempo. Tempo derives span metrics and remote-writes them to Prometheus. Alloy collects namespace pod logs through the Kubernetes API and sends them to Loki. Grafana is preconfigured with all three data sources.

The local Kubernetes backend also receives `SIGNALHARVESTER_OPERATIONS_HEALTH_PROMETHEUS_BASE_URL=http://prometheus:9090`. The verification-pending Operations Health Engine uses only its fixed allowlisted PromQL set for periodic Health Snapshots; unavailable Prometheus evidence degrades to explicit uncertainty and does not affect business processing.

## Operational dashboard

The provisioned **SignalHarvester Overview** dashboard includes:

- backend replica availability and restarts;
- Analysis outbox pending depth, oldest-pending age, batch behavior, Kafka publication latency, and database-operation latency;
- HTTP request rate and average latency;
- Collection Run and external-source outcomes;
- Analysis throughput and duration;
- Kafka consumer lag from Redpanda public metrics;
- JVM heap usage;
- PostgreSQL/JDBC aggregate latency derived from OpenTelemetry spans;
- backend logs from Loki.

Event Explorer and Processing Flow remain application diagnostics for one concrete run/item/event. Grafana is intentionally focused on aggregate system behavior.

## Redpanda topics

`redpanda-topic-provisioner` enables consumer-group lag metrics and explicitly creates the current application/DLQ topics with three partitions and replication factor one. The job is intentionally visible and terminal: if provisioning fails, the deployment should expose that failure instead of relying silently on auto-creation.

## Frontend boundary

A real frontend image is not built by this repository. When `signalharvester-web:local` is available in the cluster, follow [`frontend/README.md`](frontend/README.md).

Backend/infrastructure verification can pass without that image, but full platform R24 cannot be considered accepted until the protected frontend workload has also been verified.

## Deterministic repository tests

The routine infrastructure tests do not require Kubernetes:

```bash
./infra/kubernetes/run_tests.sh
```

They validate resource references, versioned images, secret separation, backend security/probe wiring, observability data-source wiring, dashboard JSON, and the backend container contract. The canonical repository gate also runs them through `./run_checks.sh`.

## Troubleshooting stale local cluster nodes

`verify-local.sh` fails before workload rollout checks when any Kubernetes node is not `Ready`. Workload readiness and local-path PersistentVolume scheduling are not reliable while a node is `NotReady` or `unreachable`. Inspect the cluster first:

```bash
kubectl get nodes -o wide
```

For k3d, a stopped or transiently broken local cluster may recover after a cluster restart:

```bash
k3d cluster stop signalharvester
k3d cluster start signalharvester
kubectl get nodes -o wide
```

If nodes remain unavailable, recreate the disposable local cluster with the same topology originally used for acceptance, then recreate SignalHarvester secrets, re-import the local backend image, apply the manifests, and rerun verification. Recreating only individual stateful pods is not sufficient when their local PersistentVolumes are bound to unavailable nodes.

## Reset

Delete all local SignalHarvester Kubernetes resources and PVCs by deleting the namespace:

```bash
kubectl delete namespace signalharvester
```

This destroys local PostgreSQL, Redpanda, Prometheus, Loki, Tempo, and Grafana state.

Use this full reset for disposable acceptance environments when durable test history must also be removed. Backend resilience scenarios and companion frontend live/deployed browser acceptance may intentionally leave durable Collection/Analysis/Results or retained Event Observation records after temporary configuration and fixtures are cleaned up. Those records can contain source URLs that are no longer reachable after a temporary fixture stops. The published application contracts do not provide a bulk test-history cleanup operation; do not add one only to simplify acceptance cleanup.
