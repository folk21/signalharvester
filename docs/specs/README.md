---
type: Specification Guide
title: Change specifications
description: SignalHarvester specification hierarchy, lifecycle, and minimal metadata conventions.
---
# Change specifications

`docs/specs/` contains specifications for significant planned or in-progress changes to the SignalHarvester backend. A specification defines intended delta/acceptance targets; it is **not** canonical documentation of what the repository already does.

Current implementation truth belongs in [`../ARCHITECTURE.md`](../ARCHITECTURE.md), [`../IMPLEMENTATION.md`](../IMPLEMENTATION.md), [`../CONFIGURATION.md`](../CONFIGURATION.md), [`../USAGE.md`](../USAGE.md), and owning module/contract documentation.

Frontend implementation specifications belong in the separate `signalharvester-ui/docs/specs/` tree. This backend umbrella may state cross-project product/API requirements, but it must not become the owner of React/UI implementation detail.

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

- [`active/subspecs/backend-configuration-persistence-rest.md`](active/subspecs/backend-configuration-persistence-rest.md) — PostgreSQL/Flyway source persistence and REST CRUD implementation.

Active supporting tracks:

- [`active/subspecs/backend-project-structure.md`](active/subspecs/backend-project-structure.md) — modular-monolith repository structure and module boundaries;
- [`active/subspecs/backend-event-contracts.md`](active/subspecs/backend-event-contracts.md) — Kafka/Protobuf event contract and schema-evolution rules.

## Planned backend sub-specifications

Likely future bounded specs include:

- first collection-to-Kafka publication slice;
- first collection-to-results vertical slice;
- result REST/SSE and event observation;
- Kubernetes deployment and observability;
- integration/system testing hardening.

Frontend architecture/live-UI specs belong to `signalharvester-ui`, not this backend repository.
