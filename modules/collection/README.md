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

The current implementation intentionally stops at transport retrieval. Parsing/extraction, collection-run orchestration, scheduling, and Kafka publication are not implemented yet.

Module tests exercise the Micronaut-managed HTTP transport against deterministic loopback HTTP servers, including multiple absolute hosts, response-size limits, redirects, and scoped filter behavior; they also verify Micronaut's blocking executor uses Virtual Threads, resolve the complete collection wiring, and validate bounded coordination without public network access.

## Read next

- [`../AGENTS.md`](../AGENTS.md)
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md)
- [`../../docs/specs/active/subspecs/backend-project-structure.md`](../../docs/specs/active/subspecs/backend-project-structure.md)

## Security note

Configured source URLs are currently treated as trusted application configuration. Before source-management endpoints are exposed to untrusted users, add an explicit outbound destination policy for SSRF-sensitive addresses and redirects; local loopback access must remain configurable for deterministic development and tests.
