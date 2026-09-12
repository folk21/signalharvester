---
type: Module Contract
title: SignalHarvester module contract — Results
description: Planned public integration surface, ownership, invariants, and dependency rules for the results module.
---
# SignalHarvester module contract — Results

## Purpose

Own durable, queryable analyzed results and their future presentation-facing read capabilities.

## Owned responsibilities

Results persistence/read behavior is planned but not implemented yet.

## Public integration surface

### Synchronous Java API

None implemented. No `api/` package should be created until a concrete synchronous caller exists.

### REST API

None implemented.

### Events

Future result processing is expected to consume analysis event contracts, but no results consumer is implemented yet.

## Owned data

No persisted results schema is implemented yet. Future results tables belong exclusively to this module.

## Dependencies

No synchronous functional-module dependency is currently established.

## Forbidden access

Do not reach into analysis persistence or other modules' implementation packages to obtain result data. Consume owned event/public contracts instead.

## Important invariants

Future persistence must be idempotent because upstream analysis publication is not distributed exactly-once with its database transaction.

## Extension points

Create a Java `api/` package only when a real synchronous results capability is implemented. Prefer REST/SSE and event contracts for frontend/asynchronous consumers.
