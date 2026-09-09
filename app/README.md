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

`io.signalharvester.Application` is the runnable Micronaut entry point. The application is configured as the single Netty-based backend runtime and composes all functional modules.

No product REST endpoints or infrastructure integrations are wired yet.

## Read next

- [`../AGENTS.md`](../AGENTS.md)
- [`AGENTS.md`](AGENTS.md)
- [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md)
- [`../docs/IMPLEMENTATION.md`](../docs/IMPLEMENTATION.md)
