---
type: Module Overview
title: SignalHarvester common module
description: Entry point for intentionally small stable shared Java primitives and utilities.
---
# SignalHarvester common module

`common` exists for genuinely generic, stable code reused across modules. It is intentionally constrained and must not become the default location for shared-looking business models.

Read [`AGENTS.md`](AGENTS.md) before adding code here.

The module currently owns two deliberately small generic utilities:

- `SqlResources` — classpath loading for module-owned `.sql` resources without duplicated locator classes;
- `DemandDrivenPollingLoop` — JDK-only demand, delayed scheduling, and cancellation lifecycle reused by blocking polling adapters without owning their transport or domain semantics.
