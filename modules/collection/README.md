---
type: Module Overview
title: SignalHarvester collection module
description: Current implementation and developer entry point for collection-run orchestration and external-source acquisition.
---
# SignalHarvester collection module

For module ownership, published Java APIs, event boundaries, data ownership, dependency rules, invariants, and extension points, read [`contract.md`](contract.md) first.

## Current implementation

The external-source transport path is implemented around a synchronous collection-owned fetch port and a Micronaut-managed HTTP adapter. Dynamic absolute source URLs are executed on Micronaut's blocking executor; on Java 21 that executor uses Virtual Threads. `SourceFetchCoordinator` bounds the in-flight fetch window independently of Virtual Thread cost, applies backpressure while each completed payload is terminally handled, and records source-level failures without cancelling unrelated work. Successful payloads are published as they complete instead of being accumulated until every source fetch finishes, so raw response-body retention is bounded by `signalharvester.collection.max-concurrency` while only small terminal source results grow with the run size. Event publication order therefore follows fetch completion rather than configured-source order; the final run result still restores deterministic configured-source ordering.

The HTTP adapter preserves response status, `Retry-After`, source provenance, response metadata, raw bytes, and fetch timestamps. Tests use deterministic loopback HTTP servers and cover multiple hosts, redirects, response-size limits, scoped filters, and bounded coordination.

The Kafka publication path maps collection-owned data to `RawItemDiscovered`, publishes explicit Protobuf bytes with acknowledgement, uses the caller-owned `rawItemId` as the record key, and assigns a new `eventId` for each publication. Producer idempotence and `acks=all` are enabled at the transport layer.

Collection-run orchestration is implemented with explicit run identity, ordered per-source terminal outcomes, aggregate run status, deterministic raw-item identity, and durable completed-run history. Recent-history reads load the bounded run page and all of its source outcomes in two PostgreSQL queries rather than issuing per-run source queries. Run-history reads and writes use short application-owned JDBC transactions, and the persistence adapter rejects direct access outside an active transaction. Runs obtain enabled sources through the published configuration API. Fetch and publication failures are best-effort per source rather than cancelling unrelated source work.

Parsing/extraction into multiple source items, persisted monitoring profiles, scheduling, and collection retry/DLQ policy are not implemented yet. Downstream normalization/deduplication and deterministic analysis are implemented by `modules:analysis` through the Kafka flow.

## Operational API

`/api/v1/admin/collection-runs` supports manual blocking execution and bounded inspection of completed runs. Terminal run snapshots and ordered per-source outcomes are stored in the collection-owned PostgreSQL schema. This history is diagnostic/operational state and is not an atomic substitute for Kafka delivery guarantees.

## Security note

Persisted source URLs are not outbound authorization. Until an explicit configurable outbound destination policy exists, expose source management only to trusted users/environments. A future policy must address SSRF-sensitive addresses and redirects while preserving configurable loopback access for deterministic development and tests.

## Read next

- [`contract.md`](contract.md) — authoritative module boundary and integration map
- [`../AGENTS.md`](../AGENTS.md) — shared module-development rules
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) — system architecture
- [`../../docs/specs/archive/subspecs/backend-collection-run-orchestration.md`](../../docs/specs/archive/subspecs/backend-collection-run-orchestration.md) — completed run-orchestration history
- [`../../docs/specs/active/subspecs/backend-event-contracts.md`](../../docs/specs/active/subspecs/backend-event-contracts.md) — active event-contract work
