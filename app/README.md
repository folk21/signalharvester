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

No product REST endpoints or infrastructure integrations are wired yet. Future controller operations that invoke JDBC or other blocking application workflows use `@ExecuteOn(TaskExecutors.BLOCKING)`; streaming endpoints such as SSE remain reactive instead of being moved to the blocking executor mechanically.

## Read next

- [`../AGENTS.md`](../AGENTS.md)
- [`AGENTS.md`](AGENTS.md)
- [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md)
- [`../docs/IMPLEMENTATION.md`](../docs/IMPLEMENTATION.md)
