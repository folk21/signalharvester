---
type: Module Overview
title: SignalHarvester functional modules
description: Index of modular-monolith functional capabilities and their documentation entry points.
---
# SignalHarvester functional modules

SignalHarvester is organized by cohesive capability rather than repository-wide technical layers.

For each module:

- `contract.md` is the authoritative compact boundary/context map: ownership, published APIs, data ownership, dependency rules, forbidden access, invariants, and extension points;
- `README.md` is the implementation-oriented developer entry point: current state, runtime/operational notes, limitations, and navigation to deeper documentation.

When working on another module as a dependency, read its `contract.md` before its README or implementation. Read the README when you need current implementation/runtime context.

| Module | Developer overview | Boundary contract |
|---|---|---|
| `configuration` | [`README.md`](configuration/README.md) | [`contract.md`](configuration/contract.md) |
| `collection` | [`README.md`](collection/README.md) | [`contract.md`](collection/contract.md) |
| `analysis` | [`README.md`](analysis/README.md) | [`contract.md`](analysis/contract.md) |
| `results` | [`README.md`](results/README.md) | [`contract.md`](results/contract.md) |
| `event-observation` | [`README.md`](event-observation/README.md) | [`contract.md`](event-observation/contract.md) |
| `security` | [`README.md`](security/README.md) | [`contract.md`](security/contract.md) |

Read [`AGENTS.md`](AGENTS.md) before changing any functional module.
