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

No synchronous cross-module Java API is currently published by Analysis.

`AnalysisItemInspectionQuery` and `AnalysisItemInspection` live under `analysis.application` as an internal read-only application boundary used by the analysis-owned HTTP adapter and tests. The raw-item processing path is also intentionally internal and event-driven.

`RawItemProcessor`, `ContentAnalyzer`, `ContentNormalizer`, `AnalysisOutbox`, inspection queries, and repository interfaces are internal ports used to structure the module implementation, not published module contracts.

### REST API

Authoritative schema: `contracts/api-contracts/`.

Implementation adapter: `src/main/java/io/signalharvester/analysis/http/`.

The operational controller depends on the internal `AnalysisItemInspectionQuery` application boundary, not directly on persistence.

### Events

Consumes versioned `RawItemDiscovered` events, including the effective Analysis-settings snapshot when present, and produces versioned `ItemAnalyzed` / `ItemRejected` events defined under `contracts/event-contracts/src/main/proto/`.

Kafka/Protobuf adapters remain internal implementation details.

## Owned data

- PostgreSQL schema `analysis`;
- normalized-item/deduplication claim state;
- transactional terminal-event outbox rows and dispatcher lease/publication metadata.

Other modules must not query analysis tables directly.

## Dependencies

No synchronous dependency on another functional module is currently required.

Collection-to-analysis processing is asynchronous through Kafka contracts.

## Forbidden access

Consumers must not import analysis `application`, `normalization`, `rules`, `event`, `outbox`, `persistence`, `configuration`, or `http` packages.

Do not turn internal strategy/repository interfaces into published APIs solely because they are interfaces.

## Important invariants

- deduplication identity is scoped by monitoring profile;
- deterministic classification uses the immutable settings snapshot carried by the raw event and does not synchronously query Configuration;
- deployment-global keyword rules are a compatibility fallback only for legacy raw events without a settings snapshot;
- raw Kafka offsets are committed only after the Analysis state/outbox transaction commits or acknowledged Analysis dead-letter publication;
- deterministic decode/key/mapping failures are dead-lettered without retry, while application failures use a bounded retry policy;
- DLQ publication failure leaves the source offset uncommitted;
- deduplication writes require an active application-owned database transaction and Jdbi adapters must not self-commit;
- a successful raw-item transaction commits the deduplication claim/update and exact serialized terminal event outbox row atomically before the source offset is committed;
- Kafka publication happens outside database transactions through bounded expiring outbox leases;
- a post-ack publication-marker failure may republish the same event id/payload, so downstream persistence remains idempotent;
- terminal input failures advance only after acknowledged Analysis dead-letter publication;
- operator recovery validates the current Analysis consumer group and raw input topic, requires exact dead-letter-id confirmation, reuses the normal decoder/processor, and never republishes the shared raw topic or rewrites source offsets.

## Extension points

New analyzers and normalization/persistence/event adapters should normally remain internal ports. Publish a new synchronous API only for a concrete external/module caller.
