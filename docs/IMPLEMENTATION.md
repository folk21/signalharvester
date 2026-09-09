---
type: Implementation
title: SignalHarvester implementation guide
description: Current backend implementation map, runtime wiring, contract locations, tests, and known limitations.
---
# SignalHarvester implementation guide

## Purpose and ownership

This document describes accepted current implementation only. Planned behavior remains in active specifications.

## Build and runtime foundation

The repository is a Gradle multi-project modular monolith using Java 21.

Dependency and plugin versions are centralized in `gradle/libs.versions.toml`. The first implementation pins:

- Micronaut Gradle plugin 4.6.2;
- Micronaut Framework 4.10.15;
- Protocol Buffers 4.36.1;
- JUnit 5.14.4;
- Testcontainers 2.0.5.

Micronaut 4 is intentionally retained while Java 21 remains the project baseline. A future Micronaut 5 upgrade must revisit the Java baseline rather than silently changing it.

## Application composition

`app/src/main/java/io/signalharvester/Application.java` is the runnable Micronaut entry point.

`app` uses the Netty runtime and composes all functional Gradle modules. It contains no business logic.

Application configuration currently defines:

- Micronaut application name `signalharvester`;
- HTTP port from `SIGNALHARVESTER_HTTP_PORT`, defaulting to `8080`.

No REST controllers are implemented yet.

## Configuration module

`modules:configuration` now exposes the first synchronous public API under `io.signalharvester.configuration.api`.

Implemented types:

- `SourceId`;
- `SourceType`;
- `ConfiguredSource`;
- `SourceConfigurationProvider`.

The API represents effective external-source configuration for cross-module use. No persistence implementation or provider bean exists yet.

## REST/OpenAPI contract

`contracts/api-contracts/src/main/resources/openapi/signalharvester-v1.yaml` is the first external API contract.

It currently defines source configuration CRUD operations under `/api/v1/sources` and source schemas for REST, RSS, and HTML source types.

The OpenAPI contract exists before the controller implementation so the external boundary can be reviewed independently.

## Kafka/Protobuf contracts

`contracts:event-contracts` contains the first concrete versioned Protobuf schemas:

```text
common/v1/event-envelope.proto
collection/v1/raw-item-discovered.proto
```

Generated Java transport classes are Gradle build output.

The first contract test verifies a representative `RawItemDiscovered` round trip and unknown-field tolerance. Kafka adapters do not yet exist.

## Testing implementation

JUnit Platform is enabled for Java subprojects through the root Gradle build.

Current concrete tests:

- Micronaut application-context startup test;
- configuration API invariant/defensive-copy tests;
- Protobuf serialization and unknown-field tests.

`testing:integration-tests` is prepared with Testcontainers dependencies for PostgreSQL and Kafka, but no container-backed scenario exists yet.

## Infrastructure implementation

`infra/docker-compose`, `infra/kubernetes`, and `infra/observability` remain ownership placeholders. Deployable infrastructure configuration is not yet implemented.

## Known limitations

- no PostgreSQL schema, Flyway migration, or persistence adapter;
- no Kafka producer/consumer wiring;
- no source collector implementation;
- no analysis/results implementation;
- no REST controller implementation;
- no SSE implementation;
- no event-observation persistence/API;
- no OpenTelemetry instrumentation;
- no Docker Compose or Kubernetes deployment.
