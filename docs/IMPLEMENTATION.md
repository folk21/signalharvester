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

The first blocking REST controller is implemented by the configuration module. `SourceController` uses `@ExecuteOn(TaskExecutors.BLOCKING)` so PostgreSQL access is offloaded from Netty event-loop threads; SSE/streaming endpoints added later remain reactive.

## Configuration module

`modules:configuration` now exposes the first synchronous public API under `io.signalharvester.configuration.api`.

Implemented types:

- `SourceId`;
- `SourceType`;
- `ConfiguredSource`;
- `SourceConfigurationProvider`.

The API represents effective external-source configuration for cross-module use. `SourceConfigurationManager` implements the persisted CRUD use cases and is the concrete `SourceConfigurationProvider` bean.

Persistence uses an explicit JDBC adapter over Micronaut's managed Hikari `DataSource`. The configuration module owns PostgreSQL schema `configuration` and Flyway location `classpath:db/migration/configuration`; the initial migration creates `sources` and `source_settings`. `SourceConfigurationManager` owns write transaction boundaries through Micronaut JDBC transaction operations, while the JDBC adapter owns SQL/resource handling. Multi-statement source/settings writes therefore commit or roll back as one use case. Source names are intentionally not unique because `SourceId` is the stable identity.

## REST/OpenAPI contract

`contracts/api-contracts/src/main/resources/openapi/signalharvester-v1.yaml` is the first external API contract.

It currently defines source configuration CRUD operations under `/api/v1/sources` and source schemas for REST, RSS, and HTML source types.

The OpenAPI contract is implemented by configuration-owned HTTP records/controller mapping; persistence types are not exposed through REST. Request validation aligns with `ConfiguredSource` name and HTTP(S) URI invariants, and expected missing/invalid configuration failures are mapped centrally.

## Collection module

`modules:collection` now contains the first concrete external-source transport implementation.

Implemented types:

- `ExternalSourceClient` — synchronous module-owned transport boundary intended for blocking/Virtual-Thread execution;
- `FetchedSourceContent` — immutable transport result with source provenance and fetch metadata;
- `SourceFetchException` — transport-level failure preserving source identity, URI, optional HTTP status, and raw `Retry-After`;
- `ExternalSourceHttpClient` — internal synchronous collection HTTP boundary; `MicronautManagedExternalSourceHttpClient` implements it using Micronaut's managed default client and validated absolute `URI` values without String round-tripping;
- `ExternalSourceHttpFilter` — collection-specific technical request headers and sanitized transport diagnostics;
- `MicronautExternalSourceClient` — adapter that maps Micronaut responses/failures to collection-owned types without leaking Micronaut exceptions;
- `CollectionConfiguration` — Jakarta-validated runtime collection settings;
- `CollectionClockFactory` — module-owned qualified UTC clock used for deterministic transport and collection-run timestamps;
- `SourceFetchCoordinator` — bounded best-effort worker coordination on Micronaut's blocking executor, preserving deterministic result order while using Virtual Threads on Java 21;
- `CollectionRunService` — explicit collection execution over globally enabled sources through `SourceConfigurationProvider`;
- `CollectionRunRequest` / `CollectionRunResult` — temporary caller-supplied profile/category context plus run id, timing, aggregate status, and per-source terminal outcomes;
- `RawItemIdentityFactory` — deterministic SHA-256 raw-item identity over source id, requested URI, and raw payload.

The module also implements the first asynchronous publication boundary. `RawItemEventPublisher` accepts `FetchedSourceContent` plus explicit caller-owned publication metadata, including the stable raw-item identity; `KafkaRawItemEventPublisher` maps it to `RawItemDiscovered`, assigns a new event identity, serializes the generated Protobuf message to bytes, and sends an acknowledged Kafka record through a Micronaut `@KafkaClient`. The default topic is `signalharvester.collection.raw-item-discovered.v1`, the caller-owned `rawItemId` is the record key, and producer configuration uses String/byte-array serializers, `acks=all`, and Kafka producer idempotence. Generated Protobuf classes remain confined to the Kafka adapter/mapping boundary.

Source parsing/extraction, due-work scheduling, collection-run persistence/orchestration, partial-failure semantics, and Kafka consumers are intentionally still absent.

Configured source locations are constrained to absolute HTTP/HTTPS URLs without embedded credentials or fragments. The generic client follows a bounded number of redirects, normalizes Micronaut HTTP response exceptions into collection-owned failures while retaining status and retry metadata, enforces content/time limits, and prevents blocking calls on Netty event-loop threads.

## Kafka/Protobuf contracts

`contracts:event-contracts` contains the first concrete versioned Protobuf schemas:

```text
common/v1/event-envelope.proto
collection/v1/raw-item-discovered.proto
```

Generated Java transport classes are Gradle build output.

The contract test verifies a representative `RawItemDiscovered` round trip and unknown-field tolerance. Collection now publishes this contract as explicit Protobuf bytes; analysis and event-observation consumers remain pending.

## Testing implementation

JUnit Platform is enabled for Java subprojects through the root Gradle build.

Current concrete tests include:

- Micronaut application-context startup test;
- configuration API invariant/defensive-copy and source-URL safety tests;
- Protobuf serialization and unknown-field tests;
- Micronaut blocking-executor Virtual Thread verification;
- deterministic loopback managed-client HTTP tests for headers, error statuses, query preservation, redirects, and response-size enforcement;
- collection adapter tests for success, empty bodies, transport failures, HTTP status mapping, and `Retry-After`;
- bounded Virtual Thread source-coordination tests with deterministic ordering and continuation after source-level failure;
- collection-run behavior tests for success/partial/failure outcomes, Kafka correlation, publication failure continuation, and deterministic raw-item identity;
- a cross-module PostgreSQL + deterministic HTTP + Kafka Testcontainers collection-run scenario.

`modules:configuration` contains PostgreSQL Testcontainers coverage for Flyway bootstrap, CRUD/settings/provider behavior, REST validation/status mapping, and a server-level assertion that JDBC entry executes on a blocking Virtual Thread. `modules:collection` now contains a Kafka Testcontainers producer/consumer round-trip for the real `RawItemEventPublisher`, including Protobuf decoding and correlation/provenance assertions.

## Infrastructure implementation

`infra/docker-compose`, `infra/kubernetes`, and `infra/observability` remain ownership placeholders. Deployable infrastructure configuration is not yet implemented.

## Known limitations

- no Kafka consumer wiring;
- no source parsing/extraction into multiple external items;
- collection-run history is not persisted and profile/source membership is not implemented yet;
- no analysis/results implementation;
- no SSE implementation;
- no outbound SSRF/network-destination policy yet; persisted source management must remain trusted until such a policy is defined;
- no event-observation persistence/API;
- no OpenTelemetry instrumentation;
- no Docker Compose or Kubernetes deployment.
