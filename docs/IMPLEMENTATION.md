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

Dependency and plugin versions are centralized in `gradle/libs.versions.toml`; the Micronaut Platform version is kept in `gradle.properties` as `micronautVersion` because that is the Micronaut Gradle plugin's native project-wide version input. The first implementation pins:

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
- HTTP port from `SIGNALHARVESTER_HTTP_PORT`, defaulting to `8080`;
- Micronaut's blocking executor as Virtual-Thread backed on the Java 21 baseline.

No REST controllers are implemented yet. Blocking controller operations added later must use `@ExecuteOn(TaskExecutors.BLOCKING)` rather than execute JDBC or blocking HTTP work on a Netty event-loop thread. SSE/streaming endpoints remain reactive.

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

## Collection module

`modules:collection` now contains the first concrete external-source transport implementation.

Implemented types:

- `ExternalSourceClient` — synchronous module-owned transport boundary intended for blocking/Virtual-Thread execution;
- `FetchedSourceContent` — immutable transport result with source provenance and fetch metadata;
- `SourceFetchException` — transport-level failure preserving source identity, URI, optional HTTP status, and raw `Retry-After`;
- `ExternalSourceHttpClient` — internal synchronous collection HTTP boundary; `MicronautManagedExternalSourceHttpClient` implements it using Micronaut's managed default client and absolute request URIs;
- `ExternalSourceHttpFilter` — collection-specific technical request headers and sanitized transport diagnostics;
- `MicronautExternalSourceClient` — adapter that maps Micronaut responses/failures to collection-owned types without leaking Micronaut exceptions;
- `CollectionConfiguration` — Jakarta-validated runtime collection settings;
- `CollectionClockFactory` — module-owned qualified UTC clock used for deterministic transport timestamps;
- `SourceFetchCoordinator` — bounded worker coordination on Micronaut's blocking executor, preserving deterministic result order while using Virtual Threads on Java 21.

The implementation currently performs raw HTTP retrieval and bounded multi-source coordination only. Source parsing/extraction, due-work scheduling, collection-run persistence, and Kafka publication are intentionally still absent.

Configured source locations are constrained to absolute HTTP/HTTPS URLs without embedded credentials or fragments. The generic client follows a bounded number of redirects, preserves HTTP error responses through Micronaut response exceptions for collection-owned mapping, enforces content/time limits, and prevents blocking calls on Netty event-loop threads.

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

Current concrete tests include:

- Micronaut application-context startup test;
- configuration API invariant/defensive-copy and source-URL safety tests;
- Protobuf serialization and unknown-field tests;
- Micronaut blocking-executor Virtual Thread verification;
- deterministic loopback declarative-HTTP tests for headers, error statuses, query preservation, redirects, and response-size enforcement;
- collection adapter tests for success, empty bodies, transport failures, HTTP status mapping, and `Retry-After`;
- bounded Virtual Thread source-coordination tests with deterministic ordering and failure propagation.

`testing:integration-tests` is prepared with Testcontainers dependencies for PostgreSQL and Kafka, but no container-backed scenario exists yet.

## Infrastructure implementation

`infra/docker-compose`, `infra/kubernetes`, and `infra/observability` remain ownership placeholders. Deployable infrastructure configuration is not yet implemented.

## Known limitations

- no PostgreSQL schema, Flyway migration, or persistence adapter;
- no Kafka producer/consumer wiring;
- no source parsing/extraction or collection-run orchestration;
- no analysis/results implementation;
- no REST controller implementation;
- no SSE implementation;
- no outbound SSRF/network-destination policy yet; configured source management must remain trusted until such a policy is defined;
- no event-observation persistence/API;
- no OpenTelemetry instrumentation;
- no Docker Compose or Kubernetes deployment.
