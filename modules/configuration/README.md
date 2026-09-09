---
type: Module Overview
title: SignalHarvester configuration module
description: Persisted monitoring-profile, source, schedule, filter, and analysis configuration ownership.
---
# SignalHarvester configuration module

## Ownership

Own monitoring profiles, source definitions, schedules, filters, extraction settings, analysis settings, and their persistence.

## Boundary

Other modules consume effective configuration through a public Java API or event flow; they do not query configuration tables directly.

## Current state

The first public Java boundary is implemented under `io.signalharvester.configuration.api`:

- `SourceId`;
- `SourceType`;
- `ConfiguredSource`;
- `SourceConfigurationProvider`.

`ConfiguredSource` validates basic boundary invariants and defensively copies source-specific settings. Persistence, monitoring profiles, REST implementation, and concrete provider wiring are not implemented yet.

## Read next

- [`../AGENTS.md`](../AGENTS.md)
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md)
- [`../../docs/specs/active/subspecs/backend-project-structure.md`](../../docs/specs/active/subspecs/backend-project-structure.md)
