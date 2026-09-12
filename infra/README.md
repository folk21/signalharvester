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

Repository-owned Docker Compose now provides the local PostgreSQL and Kafka-compatible Redpanda infrastructure required by the backend. The backend and Web application intentionally remain host-run during development. Kubernetes and observability deployment configuration are still planned.

Start the local infrastructure from the repository root:

```bash
docker compose -f infra/docker-compose/compose.yaml up -d
```

See [`docker-compose/README.md`](docker-compose/README.md) for lifecycle, reset, and override commands.

Read [`AGENTS.md`](AGENTS.md), [`../docs/INSTALLATION.md`](../docs/INSTALLATION.md), and [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).
