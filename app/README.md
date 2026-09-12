---
type: Module Overview
title: SignalHarvester application
description: Micronaut composition-root overview for the single deployable backend application.
---
# SignalHarvester application

## Ownership

`app` is the initial single deployable backend and Micronaut composition root.

It composes functional modules and owns startup/global runtime wiring. It must not become the owner of business logic that belongs to a functional module.

## Current state

`io.signalharvester.Application` is the runnable Micronaut entry point. The application is configured as the single Netty-based backend runtime and composes all functional modules. Micronaut's `blocking` executor is explicitly Virtual-Thread backed for the Java 21 baseline.

The assembled application currently wires PostgreSQL/Flyway, Kafka-compatible messaging, source configuration CRUD, manual collection-run/history administration, and analysis inspection. Blocking controllers owned by the functional modules use `@ExecuteOn(TaskExecutors.BLOCKING)` for JDBC and synchronous collection workflows. Streaming endpoints such as future SSE adapters remain reactive instead of being moved to the blocking executor mechanically.

## Read next

- [`../AGENTS.md`](../AGENTS.md)
- [`AGENTS.md`](AGENTS.md)
- [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md)
- [`../docs/IMPLEMENTATION.md`](../docs/IMPLEMENTATION.md)
