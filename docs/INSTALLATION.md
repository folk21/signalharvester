---
type: Installation Guide
title: Installation
description: Developer-machine prerequisites and setup for the current SignalHarvester backend foundation.
---
# Installation

## Scope

This document owns backend and backend-infrastructure setup. Frontend installation belongs to the separate `signalharvester-ui` repository README.

## Current prerequisites

The current implementation requires:

- JDK 21;
- the repository Gradle Wrapper;
- PostgreSQL 16-compatible infrastructure for persisted source configuration;
- a Kafka 3-compatible broker when exercising the collection publication boundary;
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

## Local PostgreSQL

The default runtime configuration expects `jdbc:postgresql://localhost:5432/signalharvester` with the local-development credentials `signalharvester` / `signalharvester`. A disposable local database can be started with:

```bash
docker run --rm --name signalharvester-postgres \
  -e POSTGRES_DB=signalharvester \
  -e POSTGRES_USER=signalharvester \
  -e POSTGRES_PASSWORD=signalharvester \
  -p 5432:5432 postgres:16-alpine
```

Override `SIGNALHARVESTER_DB_URL`, `SIGNALHARVESTER_DB_USERNAME`, and `SIGNALHARVESTER_DB_PASSWORD` for other environments. The local defaults are not production credentials.

## Local Kafka

The collection publisher defaults to Kafka at `localhost:9092`. Until repository-owned Docker Compose is introduced, use any Kafka 3-compatible development broker and override `SIGNALHARVESTER_KAFKA_BOOTSTRAP_SERVERS` when it is not reachable at the default address.

The collection-run use case is implemented, but no public REST trigger or scheduler owns invocation yet. Kafka is exercised by the collection publisher test and the cross-module collection-run integration test until an operational trigger is introduced.

## UI setup

The companion UI is not installed from this repository. Use the `signalharvester-ui` root README for frontend prerequisites and development-server configuration.
