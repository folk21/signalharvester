---
type: Installation Guide
title: Installation
description: Developer-machine prerequisites and setup for the current SignalHarvester backend foundation.
---
# Installation

## Scope

This document owns backend and backend-infrastructure setup. Frontend installation belongs to the separate `signalharvester-web` repository README.

## Current prerequisites

The current implementation requires:

- JDK 21;
- the repository Gradle Wrapper;
- `zip` and `unzip` for FULL-archive creation/validation through `archive.sh` and `run_checks.sh`;
- PostgreSQL 16-compatible infrastructure for the currently implemented configuration, collection-history, and analysis persistence;
- a Kafka-compatible broker for the current collection -> analysis event flow;
- network access to Maven/Gradle repositories on the first dependency resolution.

A Docker-compatible runtime is required for PostgreSQL and Kafka Testcontainers integration tests.

Docker is also a convenient local infrastructure option. The backend itself depends only on configured PostgreSQL and Kafka endpoints; it does not depend on Docker as an application runtime.

## Verify Java

```bash
java -version
```

The active build toolchain is Java 21.

## Verify the Gradle Wrapper

```bash
./gradlew --version
```

If archive extraction did not preserve executable permission:

```bash
bash ./gradlew --version
```

Do not depend on a globally installed Gradle.

## Local development infrastructure

The repository owns a Docker Compose stack for PostgreSQL and a single-node Redpanda broker exposing the Kafka API required by the current backend. From the repository root:

```bash
docker compose -f infra/docker-compose/compose.yaml up -d
```

Verify that PostgreSQL and Redpanda are healthy:

```bash
docker compose -f infra/docker-compose/compose.yaml ps
```

Redpanda runs in `dev-container` mode for local development. In this mode, Kafka topics are auto-created on first use.

Production environments must provision topics explicitly. Redpanda is only a local-development implementation detail. The application contract remains Kafka + Protobuf.

The defaults match the backend runtime configuration:

- PostgreSQL: `jdbc:postgresql://localhost:5432/signalharvester`;
- database username/password: `signalharvester` / `signalharvester`;
- Kafka bootstrap server: `localhost:9092`;
- raw discovery topic: `signalharvester.collection.raw-item-discovered.v1`;
- analyzed topic: `signalharvester.analysis.item-analyzed.v1`;
- rejected topic: `signalharvester.analysis.item-rejected.v1`.

These credentials are local-development defaults only.

The Compose stack supports overrides for:

- host ports;
- database name;
- database credentials;
- advertised Kafka host;
- Redpanda Admin API port.

To use overrides:

1. copy `infra/docker-compose/.env.example` to `infra/docker-compose/.env`;
2. pass it explicitly with `docker compose --env-file infra/docker-compose/.env ...`;
3. keep backend `SIGNALHARVESTER_DB_URL` and `SIGNALHARVESTER_KAFKA_BOOTSTRAP_SERVERS` aligned with overridden Compose endpoints.

See [`../infra/docker-compose/README.md`](../infra/docker-compose/README.md) for parameterized local endpoints, explicit `.env` usage, shutdown, and volume reset commands.

## UI setup

The companion UI is not installed from this repository. Use the `signalharvester-web` root README for frontend prerequisites and development-server configuration.

## Local Kubernetes deployment

The accepted production-style local deployment requires:

- Docker or another image builder;
- a local Kubernetes cluster such as `kind` or `k3d`;
- `kubectl`;
- enough local resources for PostgreSQL, Redpanda, two backend replicas, and the observability stack.

Build the backend image from the repository root:

```bash
docker build -f app/Dockerfile -t signalharvester-backend:local .
```

Load that image into the selected local cluster when required by the distribution, then create deployment-owned secrets and apply the Kustomize target:

```bash
./infra/kubernetes/create-local-secrets.sh
kubectl apply -k infra/kubernetes
./infra/kubernetes/verify-local.sh
```

See [`../infra/kubernetes/README.md`](../infra/kubernetes/README.md) for image-loading examples, port-forward commands, reset behavior, observability access, and the separate frontend image boundary.
