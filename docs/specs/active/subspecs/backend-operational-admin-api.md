---
type: Specification
title: SignalHarvester operational admin API
description: Manual collection execution, durable run history, and read-only analysis inspection for the first technical administration UI.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: verification-pending
---
# SignalHarvester operational admin API

## Status

Implementation complete — Gradle/PostgreSQL/Kafka verification pending before archival.

## Goal

Expose the minimum backend operations needed by a technical administration UI without creating a cross-module admin domain:

- manually start a collection run;
- list and inspect durable completed collection runs and source outcomes;
- inspect analysis-owned normalized-item/deduplication state;
- keep all write/read ownership inside the modules that own the underlying behavior and data.

## Requirements

### R1 — collection owns run operations

`modules:collection` owns `/api/v1/admin/collection-runs`. Starting a run invokes the `CollectionRunner` API (implemented by `CollectionRunService`) on `TaskExecutors.BLOCKING`. Completed runs and ordered source outcomes are persisted in the collection-owned PostgreSQL schema.

### R2 — run history is restart-safe and bounded

Run inspection must query PostgreSQL rather than process-local memory. List operations are bounded to 1..200 records and return newest runs first.

### R3 — analysis inspection is read-only

`modules:analysis` owns `/api/v1/admin/analysis/items`. It exposes only existing durable deduplication/provenance state from `analysis.normalized_item_claims`. It must not invent classification/score persistence that does not yet exist.

### R4 — module boundaries remain intact

No admin module may query collection or analysis tables. Controllers delegate to owning application/persistence boundaries. REST DTOs do not expose JDBC or generated Protobuf types.

### R5 — operational APIs are blocking HTTP flows

JDBC and manual collection execution endpoints use `@ExecuteOn(TaskExecutors.BLOCKING)` and validated bounded inputs.

### R6 — Flyway versions remain globally unique

The collection history migration uses `V3` because configuration and analysis already own `V1` and `V2` on the shared datasource history.

## Non-goals

This slice does not add authentication/authorization, monitoring profiles, scheduler, result persistence, event explorer/SSE, analysis-result persistence, or cross-database/Kafka exactly-once semantics.

## Validation

```bash
./gradlew :modules:collection:test :modules:analysis:test :app:test --no-watch-fs
./gradlew :modules:collection:integrationTest :modules:analysis:integrationTest :testing:integration-tests:integrationTest --no-watch-fs
```

Acceptance requires REST contract validation, PostgreSQL run-history persistence/query coverage, blocking controller wiring, analysis inspection query coverage, and server-level HTTP verification of the operational endpoints. The default `test` tasks intentionally exclude `@Tag("integration")`; container-backed verification must use `integrationTest` explicitly.
