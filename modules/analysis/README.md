---
type: Module Overview
title: SignalHarvester analysis module
description: Current implementation and developer entry point for normalization, deduplication, and deterministic analysis.
---
# SignalHarvester analysis module

For module ownership, published Java APIs, event boundaries, data ownership, dependency rules, invariants, and extension points, read [`contract.md`](contract.md) first.

## Current implementation

Implemented processing includes:

- `RawItemDiscovered` Kafka consumption and adapter mapping;
- deterministic URL/text normalization and stable normalized item identity;
- PostgreSQL/Flyway-backed duplicate claims scoped by monitoring profile;
- configurable deterministic keyword analysis;
- `ItemAnalyzed` and duplicate `ItemRejected` Protobuf publication;
- unit, PostgreSQL Testcontainers, and cross-module Kafka integration coverage.

The initial analyzer uses deterministic keyword matching and does not require an external AI provider. Generated Protobuf messages and Kafka client types remain in the Kafka adapter layer; core normalization, persistence, and analyzer code use analysis-owned Java models.

Monitoring-profile-owned analysis settings, richer category-specific normalization, retry/DLQ policy, results persistence, and stronger DB/Kafka consistency remain future work.

## Operational inspection

Read-only `/api/v1/admin/analysis/items` endpoints expose durable normalization/deduplication provenance from `analysis.normalized_item_claims`. Classification, score, tags, and user-facing results remain event-only until results persistence is implemented.

## Runtime notes

The Kafka listener manually commits raw offsets only after successful terminal processing/publication. JDBC deduplication state and Kafka publication share one application transaction window for retryability, but this is not distributed exactly-once behavior. The corresponding invariant and downstream idempotency requirement are defined in [`contract.md`](contract.md).

## Read next

- [`contract.md`](contract.md) — authoritative module boundary and integration map
- [`../AGENTS.md`](../AGENTS.md) — shared module-development rules
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) — system architecture
- [`../../docs/specs/archive/subspecs/backend-analysis-normalization-deduplication.md`](../../docs/specs/archive/subspecs/backend-analysis-normalization-deduplication.md) — completed implementation history
