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

`ConfiguredSource` validates source identity and URL invariants (absolute HTTP(S), host present, no embedded credentials or fragment) and defensively copies source-specific settings. Persistence, monitoring profiles, REST implementation, and concrete provider wiring are not implemented yet. Source configuration is currently assumed to be trusted; an outbound destination policy is required before accepting untrusted source URLs.

## Read next

- [`../AGENTS.md`](../AGENTS.md)
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md)
- [`../../docs/specs/active/subspecs/backend-project-structure.md`](../../docs/specs/active/subspecs/backend-project-structure.md)
