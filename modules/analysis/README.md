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
- stateless deterministic keyword analysis driven by the immutable settings snapshot in each raw event;
- transactional PostgreSQL staging and lease-driven Kafka publication of `ItemAnalyzed` and duplicate `ItemRejected`;
- unit, PostgreSQL Testcontainers, and cross-module Kafka integration coverage.


Analysis-owned PostgreSQL adapters use Micronaut-managed Jdbi with named bindings and module-owned classpath SQL resources. Application services retain transaction ownership for deduplication and outbox state, and the outbox dispatcher keeps its bounded PostgreSQL lease semantics.

The initial analyzer uses deterministic keyword matching and does not require an external AI provider. Generated Protobuf messages and Kafka client types remain in the Kafka adapter layer; core normalization, persistence, and analyzer code use Analysis-owned Java models. `RawItemDiscovered.analysis_settings` is mapped into an immutable Analysis-owned value before processing. The deployment-global keyword configuration remains only as a compatibility fallback for legacy raw events that predate this snapshot.

Richer category-specific normalization remains future work. Controlled DLQ recovery can inspect a known Analysis DLQ position and replay the stored original key/payload through the same raw-record decoder and `RawItemProcessor` without republishing the shared raw topic.

## Operational inspection

Read-only `/api/v1/admin/analysis/items` endpoints expose durable normalization/deduplication provenance from `analysis.normalized_item_claims`. The query interface behind this controller is an internal application boundary, not a published cross-module Java API. Classification, score, tags, and user-facing results remain event-only until results persistence is implemented.

## Runtime notes

The Kafka listener uses Micronaut `SYNC_PER_RECORD`. SignalHarvester owns deterministic transport/key/mapping classification, bounded application/database retry, and terminal Analysis DLQ publication, but does not manually call `Consumer.commitSync()`. Listener-thread interruption is lifecycle cancellation and escapes before retry exhaustion or DLQ classification while preserving the interrupt flag. Any exception that escapes the listener is handled by rewinding every partition from the failed Kafka poll to its first polled offset before normal consumption resumes; this can deliberately duplicate earlier records from the same poll, which Analysis deduplication/outbox semantics absorb. The listener returns normally only after the Analysis PostgreSQL transaction commits or acknowledged Analysis dead-letter publication succeeds; Micronaut then synchronously commits that completed record. A framework commit failure remains outside the application retry/DLQ boundary and may cause normal at-least-once redelivery. The normal transaction commits the deduplication change together with the exact serialized terminal event in `analysis.event_outbox`.

A background dispatcher leases a bounded batch, releases the database transaction, and renews each row's exact-token lease immediately before that row is sent so earlier batch entries cannot consume its publication ownership window. Renewal completes before Kafka I/O and requires the persisted lease to still be live at the renewal instant; an expired former owner cannot resurrect its lease merely because its old token is still stored. If ownership has already moved to another replica, the stale dispatcher also skips that row. The dispatcher then restores the persisted trace context, sends the stored bytes to Kafka, and records `published_at` or retry metadata in a separate short transaction. Ordinary retry metadata is written only while the same exact-token lease is still live at the failure instant; if the send outlives the lease, the expired owner leaves the row immediately reclaimable instead of creating a new backoff window. Lifecycle interruption is not classified as an ordinary publication failure: interruption escapes with the flag preserved, stops the current claimed batch, and leaves unfinished rows to lease-based recovery. Lease expiry allows another replica to recover abandoned work. If Kafka acknowledgement succeeds but the publication marker cannot be persisted, the same event may be sent again with the same event id and payload. Downstream consumers therefore retain their idempotent at-least-once behavior. Analysis also records low-cardinality processing and outbox telemetry. A separate metrics sampler reads global unpublished-row count and oldest-pending age without participating in dispatcher ownership; sampling failure is observability-only. The dispatcher records non-empty batch size/duration, Kafka-send latency, and short outbox database-operation latency using bounded operation/outcome tags. The corresponding consistency invariant is defined in [`contract.md`](contract.md).

## Read next

- [`contract.md`](contract.md) — authoritative module boundary and integration map
- [`../AGENTS.md`](../AGENTS.md) — shared module-development rules
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) — system architecture
- [`../../docs/specs/archive/subspecs/backend-analysis-normalization-deduplication.md`](../../docs/specs/archive/subspecs/backend-analysis-normalization-deduplication.md) — completed implementation history
