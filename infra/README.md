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

Ownership directories exist but deployable infrastructure configuration is not yet implemented.

Read [`AGENTS.md`](AGENTS.md), [`../docs/INSTALLATION.md`](../docs/INSTALLATION.md), and [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).
