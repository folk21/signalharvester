---
type: Specification
title: Backend Kubernetes and infrastructure observability deployment
description: Package the backend for Kubernetes, provide local PostgreSQL/Redpanda and Prometheus/Loki/Tempo/Grafana infrastructure, and establish reproducible deployment verification.
document_role: subspec
spec_status: verification-pending
parent: ../spec-signal-harvester-platform.md
---
# Backend Kubernetes and infrastructure observability deployment

## Status

Implementation complete for the backend-owned deployment boundary. Developer Kubernetes verification is pending. Full umbrella `DEPLOYMENT.KUBERNETES` acceptance additionally requires a real frontend image from the separate `signalharvester-web` repository.

## Feature scope

- `DEPLOYMENT.KUBERNETES`
- `OBSERVABILITY.INFRASTRUCTURE`
- `DELIVERY.FRONTEND_BACKEND_BOUNDARY`

## Goal

Run the current SignalHarvester backend and its required infrastructure in a reproducible local Kubernetes environment while collecting application/runtime metrics, container logs, distributed traces, Kafka lag, and Kubernetes workload state into a provisioned Grafana workspace.

The deployment must preserve the modular-monolith architecture: there is one backend application workload, not one deployment per Java module.

## Current state

The repository now contains:

- a reproducible Java 21 backend container build;
- Kubernetes workloads for the backend, PostgreSQL, and single-node Redpanda;
- explicit provisioning of current application and DLQ topics with three partitions;
- deployment-owned runtime secrets created outside source control;
- security environment activation, restrictive outbound-source access, readiness/liveness probes, and bounded resource settings;
- Prometheus, Loki, Tempo, Grafana, Grafana Alloy, and kube-state-metrics manifests;
- Tempo span-metric generation and Prometheus remote-write integration;
- provisioned Grafana data sources and an initial SignalHarvester operational dashboard;
- deterministic repository tests for infrastructure assets plus a live-cluster verification script;
- a separate frontend workload boundary that expects a real image owned by `signalharvester-web`.

The current developer gate has not yet run against these files, and no local Kubernetes acceptance has yet been reported in this chat.

## Requirements

### K1 — one backend deployable

Kubernetes must run one logical SignalHarvester backend application deployment composed from the existing modular monolith. Functional Java modules must not become independent workloads merely because Kubernetes is introduced.

The backend image must run on Java 21, use a non-root runtime user, and be reproducible from repository source with the Gradle wrapper.

### K2 — explicit runtime configuration and secrets

Deployment configuration must use environment variables/configuration rather than image-specific hardcoded infrastructure addresses or credentials.

JWT signing, CSRF signing, bootstrap ADMIN credentials, PostgreSQL credentials, and Grafana administration credentials must not be committed in manifests. The local workflow must create them through Kubernetes Secret objects from generated or explicitly supplied values.

The backend Kubernetes workload must enable the accepted `security` environment so authentication/RBAC and secure outbound destination policy are active.

### K3 — PostgreSQL and Redpanda local dependencies

The local cluster must provide:

- PostgreSQL with persistent local-cluster storage;
- a Kafka-compatible single-node Redpanda broker with persistent local-cluster storage;
- explicit topic provisioning for all current application and dead-letter topics;
- multiple topic partitions so later multi-replica consumer acceptance can exercise real partition parallelism.

Consumer-group lag metrics must be enabled on Redpanda for infrastructure observability.

### K4 — Kubernetes health and resources

Backend pods must expose the existing application liveness/readiness endpoints as Kubernetes probes. Workloads must declare bounded CPU/memory requests and limits appropriate to a local development cluster.

The initial backend deployment should run multiple replicas so stateless JWT behavior, Flyway coordination, scheduler leases, and Analysis outbox leases remain visible under a multi-replica runtime. Correctness of those failure/coordination paths is validated by the later system-resilience stage rather than inferred from replica count alone.

### K5 — metrics

Prometheus must scrape at least:

- backend `/prometheus` metrics;
- Redpanda public metrics;
- Kubernetes workload state through kube-state-metrics.

The stack must make request rate/latency, Collection outcomes, source fetch outcomes, Analysis outcomes/duration, JVM indicators, Kafka consumer lag, replica availability, and restart counts queryable.

### K6 — traces and PostgreSQL latency view

The backend must export OpenTelemetry traces to Tempo through OTLP in Kubernetes.

Tempo must generate aggregate span metrics and remote-write them to Prometheus so JDBC/PostgreSQL operation latency can be inspected as aggregate infrastructure telemetry without adding query-specific application metric labels.

Trace export remains auxiliary and must not become a correctness dependency.

### K7 — logs

Backend and infrastructure workloads continue writing normal process logs to stdout/stderr. Grafana Alloy must collect SignalHarvester namespace pod logs through Kubernetes APIs and push them to Loki without requiring file paths or logging agents inside application containers.

Grafana must connect log records containing `trace_id` to the Tempo data source where possible.

### K8 — provisioned Grafana workspace

Grafana must start with provisioned Prometheus, Loki, and Tempo data sources and an initial read-only SignalHarvester dashboard covering the operational dimensions required by umbrella R19.

Application Event Explorer/Processing Flow remain the source for domain/event reconstruction; Grafana remains technical infrastructure/runtime observability.

### K9 — independent frontend boundary

The backend repository must not build frontend source. It may define the Kubernetes runtime boundary expected for a separately built frontend image.

The frontend workload must remain a separate Kustomize target and must not make backend-only infrastructure deployment depend on the frontend repository. Full umbrella R24 acceptance nevertheless requires a real `signalharvester-web` image to be loaded and verified in the local cluster.

### K10 — deterministic and live verification

Routine repository verification must include deterministic infrastructure-asset tests that require neither Kubernetes nor public network access.

Live Kubernetes verification remains opt-in and must verify workload rollout plus backend readiness, Prometheus backend/Redpanda/Kubernetes targets, and Grafana availability. Trace/log inspection requires generated application traffic and may be verified through Grafana/Tempo/Loki after deployment.

## Deployment sequence

1. Build `signalharvester-backend:local` from `app/Dockerfile`.
2. Load the backend image into the chosen local Kubernetes distribution when its runtime does not share Docker images automatically.
3. Create/update runtime secrets with `infra/kubernetes/create-local-secrets.sh`.
4. Apply `infra/kubernetes` through Kustomize.
5. Wait for PostgreSQL, Redpanda, observability workloads, topic provisioning, and backend rollout.
6. Run `infra/kubernetes/verify-local.sh`.
7. Generate normal SignalHarvester traffic and inspect the provisioned Grafana dashboard, Loki logs, and Tempo traces.
8. When a real `signalharvester-web:local` image is available, apply `infra/kubernetes/frontend` and verify the protected browser workflow separately.

## Failure semantics

- Missing runtime secrets must prevent affected workloads from becoming ready rather than falling back to repository credentials.
- Backend startup may retry through normal Kubernetes restart behavior while PostgreSQL/Redpanda become available; readiness must not report a pod available before the application probe succeeds.
- Observability component failure must not alter backend business correctness.
- Failure to provision topics must leave the provisioning Job failed and visible rather than silently relying on topic auto-creation.
- A missing frontend image blocks only frontend workload verification; it must not invalidate backend/infrastructure cluster operation.

## Non-goals

This slice does not:

- introduce Helm, an operator framework, or GitOps tooling;
- create one Kubernetes workload per Java functional module;
- define production HA PostgreSQL/Redpanda topology;
- define production TLS/Ingress/certificate management;
- implement cloud-specific storage classes or load balancers;
- add KEDA or autoscaling policy;
- perform the later restart/lag/DLQ/outbox resilience acceptance itself;
- move frontend image ownership into this repository.

## Validation

Acceptance of the backend-owned implementation requires at least:

1. `./infra/kubernetes/run_tests.sh` passes;
2. `./run_checks.sh` passes;
3. backend image builds successfully from `app/Dockerfile`;
4. `kubectl apply -k infra/kubernetes` succeeds against a local `kind` or `k3d`-style cluster after secrets and image loading;
5. all backend/infrastructure workloads reach ready state and topic provisioning completes;
6. `./infra/kubernetes/verify-local.sh` passes;
7. Prometheus reports healthy backend, Redpanda, and kube-state-metrics scrape targets;
8. generated application traffic becomes visible in Loki and Tempo and the provisioned Grafana dashboard renders application/runtime metrics;
9. PostgreSQL/JDBC span latency metrics become queryable after traced database traffic.

Full umbrella `DEPLOYMENT.KUBERNETES` acceptance additionally requires the separate frontend image/workload path in K9. This backend sub-spec may be archived once its backend-owned implementation is developer-verified, while umbrella R24 remains explicitly incomplete until the frontend portion is accepted.
