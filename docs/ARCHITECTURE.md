---
type: Architecture
title: SignalHarvester architecture
description: Stable backend system boundaries, module ownership, communication contracts, persistence boundaries, and deployment direction.
---
# SignalHarvester architecture

## Purpose

This document owns stable accepted architecture. Active specs describe intended changes and may temporarily be more detailed while work is in progress.

## Core architectural decision

SignalHarvester starts as a modular monolith: one Micronaut backend deployable composed from cohesive Gradle modules. Module boundaries are designed so extraction into separate services is possible later without making distributed deployment the default.

## Module ownership

Functional modules own complete capabilities rather than repository-wide technical layers. The initial module set is:

- configuration;
- collection;
- analysis;
- results;
- event observation.

Each module owns its model/use cases, persistence, adapters, tests, and public API. Cross-module synchronous access uses public Java contracts; internal implementation packages and private database tables are not cross-module APIs.

## Communication boundaries

- in-process synchronous collaboration: Java interfaces/types;
- asynchronous integration: Kafka + versioned Protobuf;
- external application API: REST + JSON/OpenAPI;
- live browser updates: SSE + JSON.

The browser never connects directly to Kafka or PostgreSQL.

## Persistence boundary

One PostgreSQL instance is sufficient initially, but schemas/tables remain module-owned. Flyway migrations belong to the owning module. Direct cross-module table access is forbidden.

Transactional outbox and idempotent-consumer patterns are introduced where event/data atomicity or duplicate delivery requires them.

## External collection boundary

Collection is configuration-driven where practical. External I/O remains behind testable boundaries. The generic HTTP transport uses Micronaut's managed low-level HTTP client with absolute request URIs and a synchronous module-facing contract executed on Micronaut's blocking executor. On the Java 21 baseline that executor uses Virtual Threads, which keeps collection workflows imperative without blocking Netty event-loop threads.

Collection concurrency remains explicitly bounded independently of thread cost. A collection run treats source-level fetch/publication failures as best-effort outcomes: failure of one source does not cancel unrelated source work. The explicit collection run id is the correlation id for raw-item events produced by that run. HTTP connect/read/request timeouts, response-size limits, redirect limits, and connection-pool limits remain explicit runtime configuration. Client filters own only cross-cutting transport concerns; collection adapters own status/error interpretation, including normalization of Micronaut response exceptions. Configured targets currently assume trusted application configuration; before source-management APIs are exposed to untrusted users, outbound network destination policy must address SSRF-sensitive loopback, link-local, private-network, and redirect targets without breaking deterministic local development sources.

## HTTP server execution boundary

Micronaut Netty event-loop threads must not run blocking application work. REST controller methods or classes that invoke JDBC, blocking HTTP, or other imperative blocking workflows use `@ExecuteOn(TaskExecutors.BLOCKING)`. On the Java 21 baseline this means Virtual Threads. True streaming endpoints such as SSE keep their `Publisher`/reactive execution model and are not moved to blocking execution mechanically. Expected API/domain failures should be translated at the HTTP boundary through Micronaut `ExceptionHandler` implementations rather than repeated controller `try/catch` blocks; handlers and filters remain non-blocking unless explicitly offloaded.

## UI boundary

The web UI lives in the separate `signalharvester-ui` repository. This repository owns backend REST/OpenAPI and SSE contracts; the UI repository owns React/TypeScript implementation and UI-specific specifications.

## Observability and deployment

Kubernetes is the target deployment environment. OpenTelemetry is the telemetry standard; Prometheus, Loki, Tempo, and Grafana are the initial observability stack.

Application Event Explorer views explain domain/event flow. Grafana explains infrastructure/runtime health. They should complement rather than duplicate each other.

## Future extraction

A module becomes a separately deployed service only for a concrete reason such as independent scaling, failure isolation, security boundary, independent release ownership, or materially different resource requirements.
