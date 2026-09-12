---
type: Module Overview
title: SignalHarvester results module
description: Developer entry point for the planned persisted result/read-model capability.
---
# SignalHarvester results module

For planned ownership, dependency constraints, invariants, and future integration boundaries, read [`contract.md`](contract.md) first.

## Current implementation

Only the Gradle/source skeleton exists. Results persistence, result-facing REST/SSE APIs, and event consumption are not implemented yet.

Implementation should begin from the boundary and idempotency constraints in [`contract.md`](contract.md), rather than by reading private analysis or collection persistence.

## Read next

- [`contract.md`](contract.md) — authoritative module boundary and integration map
- [`../AGENTS.md`](../AGENTS.md) — shared module-development rules
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) — system architecture
- [`../../docs/specs/active/subspecs/backend-project-structure.md`](../../docs/specs/active/subspecs/backend-project-structure.md) — remaining structural acceptance work
