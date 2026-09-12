---
type: Module Overview
title: SignalHarvester event observation module
description: Developer entry point for the planned technical event-observation capability.
---
# SignalHarvester event observation module

For planned ownership, dependency constraints, invariants, and future integration boundaries, read [`contract.md`](contract.md) first.

## Current implementation

Only the Gradle/source skeleton exists. Event-history projections, correlation/flow reconstruction, browser-facing diagnostic APIs, and SSE behavior are not implemented yet.

Future implementation should consume published event contracts and expose observation-owned projections; it must not become a backdoor into other modules' implementation packages or private tables. The authoritative rule is defined in [`contract.md`](contract.md).

## Read next

- [`contract.md`](contract.md) — authoritative module boundary and integration map
- [`../AGENTS.md`](../AGENTS.md) — shared module-development rules
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) — system architecture
- [`../../docs/specs/active/subspecs/backend-project-structure.md`](../../docs/specs/active/subspecs/backend-project-structure.md) — remaining structural acceptance work
