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

A Docker-compatible runtime is required for the PostgreSQL and Kafka Testcontainers integration tests. Docker is also a convenient way to run local development infrastructure, but the backend uses configured PostgreSQL and Kafka endpoints rather than depending on Docker itself.

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

Redpanda runs in `dev-container` mode for local development. That mode enables Kafka topic auto-creation, so the current versioned topics are created on first use. Production environments must provision topics explicitly. Redpanda is a local-development implementation detail; the application contract remains Kafka + Protobuf.

The defaults match the backend runtime configuration:

- PostgreSQL: `jdbc:postgresql://localhost:5432/signalharvester`;
- database username/password: `signalharvester` / `signalharvester`;
- Kafka bootstrap server: `localhost:9092`;
- raw discovery topic: `signalharvester.collection.raw-item-discovered.v1`;
- analyzed topic: `signalharvester.analysis.item-analyzed.v1`;
- rejected topic: `signalharvester.analysis.item-rejected.v1`.

These credentials are safe local-development defaults only. The Compose file supports local host-port, database-name, credential, advertised-host, and Redpanda-admin-port overrides. Copy `infra/docker-compose/.env.example` to `infra/docker-compose/.env` and pass it explicitly with `docker compose --env-file infra/docker-compose/.env ...`. Keep the backend `SIGNALHARVESTER_DB_URL` and `SIGNALHARVESTER_KAFKA_BOOTSTRAP_SERVERS` aligned with any overridden Compose host ports/database name.

See [`../infra/docker-compose/README.md`](../infra/docker-compose/README.md) for parameterized local endpoints, explicit `.env` usage, shutdown, and volume reset commands.

## UI setup

The companion UI is not installed from this repository. Use the `signalharvester-web` root README for frontend prerequisites and development-server configuration.
