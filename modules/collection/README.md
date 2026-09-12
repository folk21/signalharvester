---
type: Module Overview
title: SignalHarvester collection module
description: Explicit collection-run orchestration, configurable external-source access, and raw-item event publication ownership.
---
# SignalHarvester collection module

## Ownership

Own due-work discovery, collection-run lifecycle, external HTTP/RSS/HTML access, parsing/extraction orchestration, and publication of discovered-item events.

## Boundary

External I/O must remain testable with deterministic fake sources. The generic HTTP path uses Micronaut's managed low-level HTTP client for dynamic absolute source URLs through a synchronous collection-owned contract executed on Micronaut's blocking executor. On Java 21 that executor uses Virtual Threads, keeping orchestration imperative while protecting Netty event-loop threads. Concurrency remains bounded independently of Virtual Thread cost.

## Current state

The first external-source transport boundary is implemented:

- `ExternalSourceClient` defines synchronous raw external-source fetching behind a module-owned interface;
- `ExternalSourceHttpClient` is the internal blocking HTTP boundary and accepts validated `URI` values; `MicronautManagedExternalSourceHttpClient` executes those absolute requests through Micronaut's managed default client;
- `ExternalSourceHttpFilter` owns common technical request headers and sanitized response diagnostics;
- `MicronautExternalSourceClient` maps Micronaut transport responses/exceptions to collection-owned results/failures and preserves status/`Retry-After` metadata;
- `FetchedSourceContent` preserves source provenance, response metadata, raw bytes, and fetch time;
- `CollectionConfiguration` validates the configurable concurrency limit through Jakarta Validation;
- `CollectionClockFactory` provides the qualified UTC clock used by collection transport timestamps;
- `SourceFetchCoordinator` runs a bounded number of workers on Micronaut's blocking executor, preserves input order, and records source-level failures without cancelling unrelated work.

The module now also owns the first Kafka publication boundary:

- `RawItemEventPublisher` is the collection-owned API used by run orchestration;
- `RawItemPublicationContext` supplies caller-owned raw-item identity plus correlation/profile/category/trace metadata without making the Kafka adapter own run or idempotency semantics;
- `RawItemDiscoveredMapper` keeps generated Protobuf types inside the Kafka adapter boundary;
- `KafkaRawItemEventPublisher` performs acknowledged publication of explicit Protobuf bytes;
- `CollectionKafkaConfiguration` owns the configurable versioned raw-item topic;
- the Kafka record key is the caller-owned `rawItemId`, while each publication gets a new `eventId`; producer idempotence plus `acks=all` are enabled at the transport layer.

The first collection-run application use case is also implemented. Its published Java surface lives under `io.signalharvester.collection.api`: `CollectionRunner` executes runs, `CollectionRunHistory` reads durable history, and the run request/result/status types are the API contract data. `CollectionRunService` and `CollectionRunHistoryQuery` are internal implementations.

- `CollectionRunService` obtains globally enabled sources only through `SourceConfigurationProvider`;
- every execution receives an explicit `collectionRunId`, reused as Kafka correlation id;
- `CollectionRunRequest` temporarily supplies monitoring-profile/category context until persisted profiles exist;
- source fetch and Kafka publication are best-effort per source, producing deterministic `PUBLISHED`, `FETCH_FAILED`, or `PUBLICATION_FAILED` outcomes;
- aggregate run status is `SUCCEEDED`, `PARTIALLY_SUCCEEDED`, or `FAILED`;
- `RawItemIdentityFactory` derives a stable SHA-256 raw-item identity from source id, requested URI, and raw bytes so identical rediscovery keeps item identity across runs;
- `CollectionRunResult` exposes run timing and ordered terminal source outcomes; completed snapshots are persisted for operational inspection.

Parsing/extraction into multiple source items, persisted monitoring profiles, scheduling, and collection retry/DLQ policy are not implemented yet. Manual collection triggering and completed-run inspection are available through the operational API. Downstream normalization/deduplication and minimal deterministic analysis are now implemented by `modules:analysis`.

Module tests exercise the Micronaut-managed HTTP transport against deterministic loopback HTTP servers, including multiple absolute hosts, response-size limits, redirects, and scoped filter behavior; they also verify Micronaut's blocking executor uses Virtual Threads, validate bounded best-effort coordination and collection-run status/correlation semantics, verify deterministic raw-item identity, verify Protobuf event mapping/serialization, and include a Kafka Testcontainers producer/consumer round-trip. `testing:integration-tests` covers both persisted enabled-source -> deterministic HTTP -> Kafka collection runs and the downstream raw Kafka -> analysis terminal-event chain.

See [`contract.md`](contract.md) for the compact integration/context map. Internal interfaces such as `ExternalSourceClient` and `RawItemEventPublisher` are implementation ports, not published module APIs.

## Read next

- [`../AGENTS.md`](../AGENTS.md)
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md)
- [`../../docs/specs/archive/subspecs/backend-collection-run-orchestration.md`](../../docs/specs/archive/subspecs/backend-collection-run-orchestration.md)
- [`../../docs/specs/active/subspecs/backend-event-contracts.md`](../../docs/specs/active/subspecs/backend-event-contracts.md)

## Security note

Source-management REST endpoints now persist configured URLs, but persistence is not outbound authorization. Until an explicit configurable outbound destination policy exists, expose source management only to trusted users/environments; the future policy must cover SSRF-sensitive addresses and redirects while preserving configurable loopback access for deterministic development and tests.


## Operational API

The module owns `/api/v1/admin/collection-runs` for manual blocking execution and bounded inspection of completed runs. Terminal run snapshots and ordered per-source outcomes are stored in the collection-owned PostgreSQL schema. This history is diagnostic/operational state and is not an atomic substitute for Kafka delivery guarantees.
