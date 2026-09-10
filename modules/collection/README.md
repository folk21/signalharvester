---
type: Module Overview
title: SignalHarvester collection module
description: Collection orchestration and configurable external-source access ownership.
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
- `SourceFetchCoordinator` runs a bounded number of workers on Micronaut's blocking executor and preserves input order.

The module now also owns the first Kafka publication boundary:

- `RawItemEventPublisher` is the collection-owned API used by later orchestration;
- `RawItemPublicationContext` supplies caller-owned raw-item identity plus correlation/profile/category/trace metadata without making the Kafka adapter own run or idempotency semantics;
- `RawItemDiscoveredMapper` keeps generated Protobuf types inside the Kafka adapter boundary;
- `KafkaRawItemEventPublisher` performs acknowledged publication of explicit Protobuf bytes;
- `CollectionKafkaConfiguration` owns the configurable versioned raw-item topic;
- the Kafka record key is the caller-owned `rawItemId`, while each publication gets a new `eventId`; producer idempotence plus `acks=all` are enabled at the transport layer.

Parsing/extraction, enabled-source collection-run orchestration, scheduling, application-level replay/idempotency semantics, and Kafka consumers are not implemented yet.

Module tests exercise the Micronaut-managed HTTP transport against deterministic loopback HTTP servers, including multiple absolute hosts, response-size limits, redirects, and scoped filter behavior; they also verify Micronaut's blocking executor uses Virtual Threads, resolve the collection wiring, validate bounded coordination, verify Protobuf event mapping/serialization, and include a Kafka Testcontainers producer/consumer round-trip.

## Read next

- [`../AGENTS.md`](../AGENTS.md)
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md)
- [`../../docs/specs/active/subspecs/backend-collection-kafka-transport.md`](../../docs/specs/active/subspecs/backend-collection-kafka-transport.md)
- [`../../docs/specs/active/subspecs/backend-event-contracts.md`](../../docs/specs/active/subspecs/backend-event-contracts.md)

## Security note

Source-management REST endpoints now persist configured URLs, but persistence is not outbound authorization. Until an explicit configurable outbound destination policy exists, expose source management only to trusted users/environments; the future policy must cover SSRF-sensitive addresses and redirects while preserving configurable loopback access for deterministic development and tests.
