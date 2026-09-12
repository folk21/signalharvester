---
type: Module Contract
title: SignalHarvester module contract — Analysis
description: Public integration surface, ownership, invariants, and dependency rules for the analysis module.
---
# SignalHarvester module contract — Analysis

## Purpose

Own post-discovery normalization, profile-scoped deduplication, deterministic analysis, terminal analysis-event publication, and bounded operational inspection.

## Owned responsibilities

- raw-item transport mapping;
- normalization and logical-item identity;
- durable deduplication claims;
- analysis/classification;
- terminal analysis event publication;
- read-only operational inspection of normalized-item claims.

## Public integration surface

### Synchronous Java API

Authoritative package:

`src/main/java/io/signalharvester/analysis/api/`

Current interface:

- `AnalysisItemInspectionQuery` — bounded read-only operational query API.

Its returned projection is `AnalysisItemInspection` in the same package.

The raw-item processing path is intentionally not a published synchronous module API. `RawItemProcessor`, `ContentAnalyzer`, `ContentNormalizer`, `AnalysisEventPublisher`, and repository interfaces are internal ports used to structure the module implementation.

### REST API

Authoritative schema: `contracts/api-contracts/`.

Implementation adapter: `src/main/java/io/signalharvester/analysis/http/`.

The operational controller depends on `AnalysisItemInspectionQuery`, not directly on persistence.

### Events

Consumes versioned `RawItemDiscovered` events and produces versioned `ItemAnalyzed` / `ItemRejected` events defined under `contracts/event-contracts/src/main/proto/`.

Kafka/Protobuf adapters remain internal implementation details.

## Owned data

- PostgreSQL schema `analysis`;
- normalized-item/deduplication claim state.

Other modules must not query analysis tables directly.

## Dependencies

No synchronous dependency on another functional module is currently required.

Collection-to-analysis processing is asynchronous through Kafka contracts.

## Forbidden access

Consumers must not import analysis `application`, `normalization`, `rules`, `event`, `persistence`, `configuration`, or `http` packages.

Do not turn internal strategy/repository interfaces into published APIs solely because they are interfaces.

## Important invariants

- deduplication identity is scoped by monitoring profile;
- raw Kafka offsets are committed only after successful terminal processing/publication;
- current DB/Kafka transaction semantics are retryable but not distributed exactly-once;
- downstream result persistence must remain idempotent.

## Extension points

New analyzers and normalization/persistence/event adapters should normally remain internal ports. Publish a new synchronous API only for a concrete external/module caller.
