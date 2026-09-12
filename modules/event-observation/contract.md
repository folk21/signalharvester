---
type: Module Contract
title: SignalHarvester module contract — Event observation
description: Planned public integration surface, ownership, invariants, and dependency rules for the event-observation module.
---
# SignalHarvester module contract — Event observation

## Purpose

Own future operational observation/projection of published system events without becoming a generic dependency between business modules.

## Owned responsibilities

Event observation is planned but not implemented yet.

## Public integration surface

### Synchronous Java API

None implemented. No `api/` package should be created until a concrete synchronous caller exists.

### REST / SSE API

None implemented.

### Events

The module is expected to observe published versioned event contracts when implemented; it must not redefine those schemas.

## Owned data

No persisted observation projection is implemented yet.

## Dependencies

No synchronous functional-module dependency is currently established.

## Forbidden access

Do not use event observation as a backdoor for cross-module domain access. It must consume published events/contracts rather than implementation packages or private tables.

## Important invariants

Observed data is a projection of published events and must not become the source of truth for another module's domain state unless explicitly redesigned.

## Extension points

Future SSE/read APIs should expose observation-owned projections while preserving the source event contracts as authoritative transport schemas.
