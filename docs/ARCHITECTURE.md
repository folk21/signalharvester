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

Collection is configuration-driven where practical. External I/O remains behind testable boundaries and is a primary use case for straightforward blocking Java code running on Virtual Threads with bounded concurrency and explicit timeouts/retries.

## UI boundary

The web UI lives in the separate `signalharvester-ui` repository. This repository owns backend REST/OpenAPI and SSE contracts; the UI repository owns React/TypeScript implementation and UI-specific specifications.

## Observability and deployment

Kubernetes is the target deployment environment. OpenTelemetry is the telemetry standard; Prometheus, Loki, Tempo, and Grafana are the initial observability stack.

Application Event Explorer views explain domain/event flow. Grafana explains infrastructure/runtime health. They should complement rather than duplicate each other.

## Future extraction

A module becomes a separately deployed service only for a concrete reason such as independent scaling, failure isolation, security boundary, independent release ownership, or materially different resource requirements.
