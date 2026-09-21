---
type: Module Overview
title: SignalHarvester collection module
description: Current implementation and developer entry point for collection-run orchestration and external-source acquisition.
---
# SignalHarvester collection module

For module ownership, published Java APIs, event boundaries, data ownership, dependency rules, invariants, and extension points, read [`contract.md`](contract.md) first.

## Current implementation

The external-source transport path is implemented around a synchronous collection-owned fetch port and a Micronaut-managed HTTP adapter. Dynamic absolute source URLs are executed on Micronaut's blocking executor; on Java 21 that executor uses Virtual Threads. `SourceFetchCoordinator` bounds the in-flight fetch window independently of Virtual Thread cost, applies backpressure while each completed payload is terminally handled, and records source-level failures without cancelling unrelated work. Successful fetched responses are extracted as they complete instead of being accumulated until every source fetch finishes. RSS/Atom responses are parsed into bounded individual entries. REST sources may opt into RFC 6901 JSON Pointer extraction, and HTML sources may opt into CSS-selector extraction; sources without the corresponding `json.*` or `html.*` settings retain one-item passthrough behavior. Raw response-body retention remains bounded by `signalharvester.collection.max-concurrency`; RSS/Atom and generic source extraction use independent per-source item bounds. Event publication order follows fetch completion and, within one feed, feed-entry order; the final run result restores deterministic configured-source order and per-source item order.

Collection also records low-cardinality run/source-fetch metrics and owns one application trace span per manual or scheduled run. `SourceFetchCoordinator` preserves Micronaut propagated context when it fans work out onto the blocking executor so downstream HTTP client spans stay connected to that run.

The HTTP adapter preserves response status, `Retry-After`, source provenance, response metadata, raw bytes, and fetch timestamps. The accepted outbound-access resolver binds DNS authorization to the Netty connection path, rejects blocked/mixed address sets in secure mode, including private and carrier-grade-NAT ranges, and revalidates redirect destinations while preserving explicit trusted-local loopback compatibility. Tests use deterministic loopback HTTP servers and cover multiple hosts, redirects, response-size limits, scoped filters, destination policy, and bounded coordination.

The Kafka publication path maps collection-owned data to `RawItemDiscovered`, publishes explicit Protobuf bytes with acknowledgement, uses the caller-owned `rawItemId` as the record key, and assigns a new `eventId` for each publication. Every newly published raw event also captures the effective Monitoring Profile Analysis settings loaded for the run, so queued/replayed work does not depend on later profile edits. Producer idempotence and `acks=all` are enabled at the transport layer.

Collection-run orchestration is implemented with persisted monitoring-profile identity, ordered profile-owned source membership, aggregate run status, deterministic raw-item identity, and durable completed-run history. Recent-history reads load the bounded run page and all of its source outcomes in two PostgreSQL queries rather than issuing per-run source queries. Run-history reads and writes use short application-owned database transactions. Micronaut-managed Jdbi executes named SQL resources inside those transactions, and the persistence adapter rejects direct access outside an active transaction. Runs resolve the persisted profile through `MonitoringProfileConfigurationProvider`, then resolve its member sources through `SourceConfigurationProvider`; disabled members are skipped. Collection itself currently exposes no synchronous Java API to other functional modules; its run/history interfaces are internal application boundaries for collection-owned adapters. Ordinary fetch and publication failures are best-effort per source rather than cancelling unrelated source work. Lifecycle interruption is run-level cancellation: wrapped interruption during acknowledged Kafka publication escapes before `PUBLICATION_FAILED` classification and stops later work.

Automatic interval scheduling is implemented with collection-owned PostgreSQL state, transactional due-work claims, renewable leases, and completion-based next-due calculation. A claimed lease that cannot start because local blocking-executor dispatch or heartbeat setup fails is released best-effort by exact lease token without advancing `next_due_at`; another replica can therefore reclaim still-due work immediately, while normal lease expiry remains the fallback if release persistence also fails. Once a scheduled run has started, lifecycle interruption cancels its heartbeat and skips normal completion so `next_due_at` is not advanced; the existing lease-expiry boundary makes the overdue schedule reclaimable by another replica. Diagnostic source testing is implemented over persisted sources and reuses the same external fetch and extraction ports as normal collection. Source tests return bounded previews and never publish Kafka events or create collection-run history. Cron/calendar schedules, custom authenticated/request-body collectors, and collection retry/DLQ policy are not implemented yet. Downstream normalization/deduplication and deterministic analysis are implemented by `modules:analysis` through the Kafka flow.

## Operational API

`POST /api/v1/sources/{sourceId}/test` performs a diagnostic test of one persisted source, including disabled sources before activation. Fetch and extraction failures are returned as diagnostic result statuses so callers can display external-source errors without treating them as backend transport failures. Preview cardinality and content length are bounded by runtime configuration.

`/api/v1/admin/collection-runs` supports manual profile-driven blocking execution and bounded inspection of completed runs. The POST request accepts only a persisted monitoring-profile UUID; category and source membership come from Configuration. Terminal run snapshots and ordered per-source outcomes are stored in the collection-owned PostgreSQL schema. This history is diagnostic/operational state and is not an atomic substitute for Kafka delivery guarantees.

## Security note

Persisted source URLs are not outbound authorization. Collection owns the accepted runtime destination policy. The default trusted-local mode preserves deterministic loopback/private development access; the `security` environment enables restrictive destination authorization and explicit CIDR overrides.

## Read next

- [`contract.md`](contract.md) — authoritative module boundary and integration map
- [`../AGENTS.md`](../AGENTS.md) — shared module-development rules
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) — system architecture
- [`../../docs/specs/archive/subspecs/backend-collection-run-orchestration.md`](../../docs/specs/archive/subspecs/backend-collection-run-orchestration.md) — completed run-orchestration history
- [`../../docs/specs/archive/subspecs/backend-source-test-generic-extraction.md`](../../docs/specs/archive/subspecs/backend-source-test-generic-extraction.md) — completed source-test and generic-extraction slice
- [`../../docs/specs/active/subspecs/backend-event-contracts.md`](../../docs/specs/active/subspecs/backend-event-contracts.md) — active event-contract work
