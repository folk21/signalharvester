---
type: Module Overview
title: SignalHarvester analysis module
description: Normalization, deduplication, relevance, classification, and scoring ownership.
---
# SignalHarvester analysis module

## Ownership

Own processing after raw discovery: transport-to-application mapping, deterministic normalization, logical-item identity, durable profile-scoped deduplication, replaceable analysis, and publication of terminal analysis outcomes.

The module owns PostgreSQL schema `analysis`; other modules must not query its deduplication tables directly.

## Boundary

The only current synchronous published Java surface is `io.signalharvester.analysis.api.AnalysisItemInspectionQuery` with its `AnalysisItemInspection` projection. The raw Kafka processing path remains asynchronous; `RawItemProcessor`, `ContentAnalyzer`, `ContentNormalizer`, event publishers, and repositories are internal module ports rather than published APIs. The operational HTTP adapter depends on the query interface instead of persistence.

Generated Protobuf messages and Kafka client/consumer types stay under `event.kafka`. Core normalization, persistence, and analyzer logic use analysis-owned Java models. `ContentAnalyzer` is the replaceable analysis boundary; the initial implementation is deterministic keyword matching and does not require an external AI provider.

The current listener manually commits raw Kafka offsets only after successful processing and acknowledged terminal publication. JDBC deduplication state and Kafka publication share one application transaction window for retryability, but that is not distributed exactly-once behavior; downstream result persistence must remain idempotent.

## Current state

Implemented:

- `RawItemDiscovered` Kafka consumption and adapter mapping;
- deterministic URL/text normalization and stable normalized item identity;
- PostgreSQL/Flyway-backed duplicate claims scoped by monitoring profile;
- configurable deterministic keyword analysis;
- `ItemAnalyzed` and duplicate `ItemRejected` Protobuf publication;
- unit, PostgreSQL Testcontainers, and cross-module Kafka integration coverage.

Monitoring-profile-owned analysis settings, richer category-specific normalization, retry/DLQ policy, results persistence, and stronger DB/Kafka consistency remain future work.

See [`contract.md`](contract.md) for the compact integration/context map.

## Read next

- [`../AGENTS.md`](../AGENTS.md)
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md)
- [`../../docs/specs/archive/subspecs/backend-analysis-normalization-deduplication.md`](../../docs/specs/archive/subspecs/backend-analysis-normalization-deduplication.md)


## Operational inspection

The module owns read-only `/api/v1/admin/analysis/items` endpoints over `analysis.normalized_item_claims`. The API intentionally exposes only durable normalization/deduplication provenance; classification, score, tags, and user-facing results remain event-only until results persistence is implemented.
