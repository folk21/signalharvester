---
type: Module Overview
title: SignalHarvester functional modules
description: Index of modular-monolith functional capabilities and their ownership boundaries.
---
# SignalHarvester functional modules

SignalHarvester is organized by cohesive capability rather than repository-wide technical layers.

| Module | Ownership |
|---|---|
| [`configuration`](configuration/README.md) | Monitoring profiles, sources, schedules, filters, and persisted configuration |
| [`collection`](collection/README.md) | Due-work orchestration and external-source collection |
| [`analysis`](analysis/README.md) | Normalization, deduplication, relevance/classification/scoring |
| [`results`](results/README.md) | Query/read model and result-facing REST/SSE behavior |
| [`event-observation`](event-observation/README.md) | Technical event history, correlation, flow reconstruction, Event Explorer support |

Read [`AGENTS.md`](AGENTS.md) before changing any functional module.
