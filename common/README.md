---
type: Module Overview
title: SignalHarvester common module
description: Entry point for intentionally small stable shared Java primitives and utilities.
---
# SignalHarvester common module

`common` exists for genuinely generic, stable code reused across modules. It is intentionally constrained and must not become the default location for shared-looking business models.

Read [`AGENTS.md`](AGENTS.md) before adding code here.

The module currently also owns `SqlResources`, the generic classpath loader used by Jdbi persistence adapters to resolve module-owned `.sql` resources without duplicating locator classes.
