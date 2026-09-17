---
type: Infrastructure Guide
title: SignalHarvester infrastructure
description: Entry point for local Docker Compose, Kubernetes deployment, and observability configuration.
---
# SignalHarvester infrastructure

## Areas

- `docker-compose/` — local development infrastructure;
- `kubernetes/` — target Kubernetes deployment configuration;
- `observability/` — OpenTelemetry/Prometheus/Loki/Tempo/Grafana configuration and dashboards.

## Current state

Repository-owned Docker Compose provides the lightweight local PostgreSQL and Kafka-compatible Redpanda development path. The accepted backend-owned Kubernetes stack packages the backend with PostgreSQL, Redpanda, Prometheus, Loki, Tempo, Grafana Alloy, kube-state-metrics, and Grafana. Its live acceptance also verifies controlled resilience and partition-bounded Kafka consumer scaling. The separate frontend repository still owns its image; `kubernetes/frontend/` defines only the runtime boundary needed for later full platform acceptance.

Start the local infrastructure from the repository root with built-in safe development defaults:

```bash
docker compose -f infra/docker-compose/compose.yaml up -d
```

The Compose file also supports explicit environment overrides through `infra/docker-compose/.env.example`; use `--env-file infra/docker-compose/.env` when a customized local file is present.

See [`docker-compose/README.md`](docker-compose/README.md) for the host-run development lifecycle and [`kubernetes/README.md`](kubernetes/README.md) for the production-style local cluster workflow.

Read [`AGENTS.md`](AGENTS.md), [`../docs/INSTALLATION.md`](../docs/INSTALLATION.md), and [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).
