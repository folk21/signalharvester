---
type: Specification
title: SignalHarvester backend processing-flow reconstruction
description: Bounded reconstruction of collection-run and item processing graphs from the Event Observation projection.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: completed
---
# SignalHarvester backend processing-flow reconstruction

## Status

Completed and verified by the developer through the canonical `./run_checks.sh` repository gate.

## Goal

Reconstruct a bounded application-level processing graph for one collection run or one item within a collection run by using Event Observation history as evidence.

The graph is diagnostic. It must distinguish directly observed facts from derived stages and from stages that the current event model cannot prove.

## Relationship to the umbrella specification

This slice implements the backend reconstruction surface for umbrella requirements R8 and R17. It builds on the accepted Event Observation history rather than adding another event store.

## Current state

Event Observation persists bounded decoded `RawItemDiscovered`, `ItemAnalyzed`, and `ItemRejected` history with correlation, item lineage, trace, and Kafka metadata. The backend can query and stream those events but does not yet provide a graph model for the Event Explorer.

## Requirements

### PF1 — bounded run and item lookup

The backend must expose processing-flow reconstruction for:

- one collection run identified by the current correlation id;
- one raw or normalized item scoped to a collection run.

Reconstruction must use the bounded Event Observation projection. It must not query another module's private tables.

### PF2 — lineage follows published event identity

A terminal Analysis event links to its raw discovery through `sourceEventId`. Reconstruction must use that identity before relying on repeated content/item identifiers.

Repeated collection of the same logical content must therefore remain separate branches when it produced separate raw discovery events.

### PF3 — evidence must be explicit

Every graph node must state how it is known:

- `OBSERVED_EVENT` — directly represented by a published event;
- `OBSERVED_KAFKA_METADATA` — transport metadata persisted with that event;
- `DERIVED_FROM_EVENT` — a stage implied by the current published event semantics;
- `NOT_OBSERVED` — an expected stage for which the current observation model has no completion evidence.

The API must not present inferred or unobserved work as directly measured work.

### PF4 — current pipeline stages

Where evidence exists, one branch may represent:

1. external source;
2. collection;
3. raw-event Kafka publication;
4. normalization;
5. deduplication;
6. analysis outcome;
7. terminal Analysis-event Kafka publication;
8. Results persistence as an expected but currently unobserved stage.

For the current Analysis implementation:

- `ItemAnalyzed` implies normalization completed, deduplication passed, and analysis completed;
- `ItemRejected` with reason `DUPLICATE` implies normalization completed, deduplication rejected the item, and content analysis was skipped;
- other future rejection reasons must not be assigned stronger stage semantics than their published payload supports.

### PF5 — diagnostic metadata and timing

Nodes must preserve relevant available metadata such as:

- event id/type and occurrence time;
- producer and trace context;
- source/profile/raw/normalized item identity;
- Kafka topic/partition/offset/key;
- terminal classification, score, or rejection reason.

When both endpoint timestamps are known, graph edges may expose elapsed milliseconds. They must not invent per-stage durations when only terminal-event timing exists.

### PF6 — graph state and bounded-history gaps

A reconstructed graph must distinguish:

- `TERMINAL_EVENT_REACHED` — every included raw branch has a terminal Analysis event and no predecessor is missing;
- `IN_PROGRESS` — at least one observed raw branch has no terminal event yet;
- `PARTIAL_HISTORY` — bounded retention/query limits or missing predecessor evidence prevent a complete observed lineage.

A collection-run query that reaches the Event Observation query bound must report that limitation explicitly.

### PF7 — REST contract

The backend must expose:

- `GET /api/v1/flows/collection-runs/{collectionRunId}`;
- `GET /api/v1/flows/collection-runs/{collectionRunId}/items/{itemId}`.

The item endpoint matches either raw or normalized item identity but remains collection-run scoped so repeated logical items from different runs are not conflated.

An unknown run or item must return `404`.

## Non-goals

This slice does not:

- add a second persistence model for flow graphs;
- publish new Kafka events solely for visualization detail;
- claim that Results persistence succeeded when no observation event proves it;
- provide OpenTelemetry/Tempo span lookup;
- provide a dedicated flow SSE stream; the existing Event Observation SSE remains the live primitive;
- reconstruct events already removed by diagnostic retention.

## Design constraints

- Flow reconstruction belongs to Event Observation because it derives only from that module's diagnostic projection.
- The graph is computed on read and is not authoritative business state.
- Raw-to-terminal lineage uses published `sourceEventId` first.
- Response ordering and node identifiers must be deterministic for stable frontend rendering.
- Reconstruction remains bounded by the existing Event Observation query limit.

## Validation

Acceptance requires:

1. unit coverage for analyzed, duplicate, in-progress, partial-history, and item-scoped reconstruction;
2. server-level REST/offload coverage for both flow endpoints and `404` behavior;
3. cross-module HTTP smoke coverage proving a real collection flow is reconstructable after Event Observation consumes it;
4. OpenAPI coverage for the graph contract;
5. canonical `./run_checks.sh` passing in the developer environment.
