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
- transactional PostgreSQL staging and lease-driven Kafka publication of `ItemAnalyzed` and duplicate `ItemRejected`;
- unit, PostgreSQL Testcontainers, and cross-module Kafka integration coverage.

The initial analyzer uses deterministic keyword matching and does not require an external AI provider. Generated Protobuf messages and Kafka client types remain in the Kafka adapter layer; core normalization, persistence, and analyzer code use analysis-owned Java models.

Monitoring-profile-owned analysis settings, richer category-specific normalization, and controlled DLQ replay remain future work.

## Operational inspection

Read-only `/api/v1/admin/analysis/items` endpoints expose durable normalization/deduplication provenance from `analysis.normalized_item_claims`. The query interface behind this controller is an internal application boundary, not a published cross-module Java API. Classification, score, tags, and user-facing results remain event-only until results persistence is implemented.

## Runtime notes

The Kafka listener manually commits raw offsets after the Analysis PostgreSQL transaction commits or after acknowledged Analysis dead-letter publication for a terminal input failure. Deterministic transport/key/mapping failures bypass retry; application/database failures retry within the configured bound. The normal transaction commits the deduplication change together with the exact serialized terminal event in `analysis.event_outbox`.

A background dispatcher leases a bounded batch, releases the database transaction, restores the trace context persisted with the outbox row, sends the stored bytes to Kafka, and then records `published_at` or retry metadata. Lease expiry allows another replica to recover abandoned work. If Kafka acknowledgement succeeds but the publication marker cannot be persisted, the same event may be sent again with the same event id and payload. Downstream consumers therefore retain their idempotent at-least-once behavior. Analysis also records low-cardinality processing and outbox-publication metrics. The corresponding consistency invariant is defined in [`contract.md`](contract.md).

## Read next

- [`contract.md`](contract.md) — authoritative module boundary and integration map
- [`../AGENTS.md`](../AGENTS.md) — shared module-development rules
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) — system architecture
- [`../../docs/specs/archive/subspecs/backend-analysis-normalization-deduplication.md`](../../docs/specs/archive/subspecs/backend-analysis-normalization-deduplication.md) — completed implementation history
