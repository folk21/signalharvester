---
type: Development Guide
title: Infrastructure development rules
description: Rules for Docker Compose, Kubernetes, observability, configuration, secrets, and reproducible local deployment.
---
# Infrastructure development rules

## Ownership

`infra/` owns deployment/development infrastructure, not application business behavior.

## Configuration and secrets

- Keep environment-specific values configurable.
- Never commit production credentials or secrets.
- Provide safe local-development defaults only where they cannot be mistaken for production security.
- Avoid hardcoded developer-machine paths.

## Deployment

Start with one backend deployment. Do not create a Kubernetes deployment per Java module unless a module is actually extracted into a service.

Docker Compose is for convenient local development/integration. Kubernetes configuration owns target deployment behavior.

## Observability

OpenTelemetry is the application telemetry standard. Prometheus, Loki, Tempo, and Grafana are the initial stack. Preserve trace/correlation context across Kafka and HTTP boundaries.
