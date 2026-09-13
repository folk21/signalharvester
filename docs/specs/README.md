---
type: Specification Guide
title: Change specifications
description: SignalHarvester specification hierarchy, lifecycle, and minimal metadata conventions.
---
# Change specifications

`docs/specs/` contains specifications for significant planned or in-progress changes to the SignalHarvester backend. A specification defines intended delta/acceptance targets; it is **not** canonical documentation of what the repository already does.

Current implementation truth belongs in [`../ARCHITECTURE.md`](../ARCHITECTURE.md), [`../IMPLEMENTATION.md`](../IMPLEMENTATION.md), [`../CONFIGURATION.md`](../CONFIGURATION.md), [`../USAGE.md`](../USAGE.md), and owning module/contract documentation.

Frontend implementation specifications belong in the separate `signalharvester-web/docs/specs/` tree. This backend umbrella may state cross-project product/API requirements, but it must not become the owner of React/UI implementation detail.

## Specification hierarchy

The active tree is deliberately shallow:

```text
active/
    spec-<umbrella>.md
    subspecs/
        <bounded-focus>.md
```

- `spec-*.md` is the active umbrella product/system target.
- `subspecs/*.md` refines one bounded technical/implementation increment.
- At most one sub-spec is the current implementation focus at a time.
- Other active sub-specs may remain as supporting tracks when they define contracts needed by the current focus.
- Completing a sub-spec does not imply the umbrella is complete.
- Do not create deeper trees without a real need.

## When to create a sub-spec

Create one when work substantially changes module boundaries, persistence, public API/event contracts, deployment, reliability behavior, or spans multiple implementation sessions and benefits from stable acceptance criteria.

Small bugs, local refactors, routine dependency maintenance, and narrow docs improvements do not require a sub-spec.

## Lifecycle

1. Keep the umbrella under `docs/specs/active/spec-*.md`.
2. Put the current bounded focus under `docs/specs/active/subspecs/`.
3. Record parent/current-focus relationships in frontmatter and Markdown links.
4. Give important requirements stable IDs where tests/reviews benefit from them.
5. Implement while preserving root/local `AGENTS.md` rules.
6. After acceptance, move stable knowledge into current-state docs.
7. Move completed sub-specs to `docs/specs/archive/subspecs/`.
8. Archive the umbrella only after its own acceptance target is complete.

Archived specs preserve historical intent but are not current source of truth and are ignored during normal implementation work unless explicitly requested.

Lifecycle location is exclusive: a specification must not exist under both `active/` and `archive/`. Archival is a move, not a copy. When a spec is moved, update the umbrella `current_focus`, this index, and any current documentation links in the same change.

`current_focus` metadata and the human-readable "current focus" text must agree. Keep only one current sub-specification at a time; supporting active specs may remain only when they still define unresolved intended behavior rather than restating accepted architecture.

## Document metadata

Managed documentation uses minimal YAML frontmatter:

- `type`;
- `title`;
- `description`.

Specification files additionally use only currently useful workflow fields:

- `document_role` — `umbrella` or `subspec`;
- `spec_status` — e.g. `active`, `verification-pending`;
- `parent` — umbrella path for a sub-spec;
- `current_focus` — current sub-spec path for an umbrella.

Do not add metadata that merely duplicates document body or Git history.

## Suggested sub-spec structure

```text
# <Change name>

## Status
## Goal
## Relationship to the umbrella specification
## Current state
## Requirements
## Scenarios
## Non-goals
## Design constraints
## Compatibility / migration
## Validation
## Implementation tasks
```

## Active specifications

Umbrella:

- [`active/spec-signal-harvester-platform.md`](active/spec-signal-harvester-platform.md) — initial observable collection/analysis platform target.

Current implementation focus:

- [`active/subspecs/backend-rss-atom-extraction.md`](active/subspecs/backend-rss-atom-extraction.md) — bounded collection-owned extraction of RSS/Atom documents into individual raw items.

Active supporting tracks:

- [`active/subspecs/backend-project-structure.md`](active/subspecs/backend-project-structure.md) — modular-monolith boundaries, published API rules, and architecture-test guardrails;
- [`active/subspecs/backend-event-contracts.md`](active/subspecs/backend-event-contracts.md) — Kafka/Protobuf event contract and schema-evolution rules.

## Recently completed sub-specifications

- [`archive/subspecs/backend-results-rest-api.md`](archive/subspecs/backend-results-rest-api.md) — public Results feed/detail REST reads over Results-owned projections, verified in the developer environment;

- [`archive/subspecs/backend-results-persistence.md`](archive/subspecs/backend-results-persistence.md) — idempotent Results-owned persistence for terminal Analysis events, verified in the developer environment;

- [`archive/subspecs/backend-configuration-persistence-rest.md`](archive/subspecs/backend-configuration-persistence-rest.md) — PostgreSQL-backed source CRUD, REST validation, and configuration-provider wiring, verified in the developer environment;
- [`archive/subspecs/backend-operational-admin-api.md`](archive/subspecs/backend-operational-admin-api.md) — manual collection execution, durable run inspection, and read-only analysis inspection, verified in the developer environment;
- [`archive/subspecs/backend-analysis-normalization-deduplication.md`](archive/subspecs/backend-analysis-normalization-deduplication.md) — normalization, profile-scoped deduplication, deterministic analysis, and terminal analysis events, verified in the developer environment;
- [`archive/subspecs/backend-collection-kafka-transport.md`](archive/subspecs/backend-collection-kafka-transport.md) — acknowledged `RawItemDiscovered` Protobuf publication boundary and Kafka round-trip, verified in the developer environment;
- [`archive/subspecs/backend-collection-run-orchestration.md`](archive/subspecs/backend-collection-run-orchestration.md) — enabled-source collection execution, run correlation, deterministic raw identity, and best-effort partial-failure behavior, verified in the developer environment.

## Planned backend sub-specifications

Likely future bounded specs include:

- result SSE/live delivery and event observation;
- Kubernetes deployment and observability;
- integration/system testing hardening.

Frontend architecture/live-UI specs belong to `signalharvester-web`, not this backend repository.
