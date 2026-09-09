---
type: Module Overview
title: SignalHarvester collection module
description: Collection orchestration and configurable external-source access ownership.
---
# SignalHarvester collection module

## Ownership

Own due-work discovery, collection-run lifecycle, external HTTP/RSS/HTML access, parsing/extraction orchestration, and publication of discovered-item events.

## Boundary

External I/O must remain testable with deterministic fake sources. Use bounded concurrency and Virtual Threads where suitable.

## Current state

The first external-source transport boundary is implemented:

- `ExternalSourceClient` defines raw external-source fetching;
- `JdkHttpExternalSourceClient` performs bounded HTTP GET requests using the JDK HTTP client;
- `FetchedSourceContent` preserves source provenance, response metadata, raw bytes, and fetch time;
- `VirtualThreadSourceFetchCoordinator` fetches independent sources concurrently using Virtual Threads with an explicit concurrency bound while preserving input order.

The current implementation intentionally stops at transport retrieval. Parsing/extraction, collection-run orchestration, scheduling, and Kafka publication are not implemented yet.

Module tests use a deterministic loopback HTTP server and do not require public network access.

## Read next

- [`../AGENTS.md`](../AGENTS.md)
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md)
- [`../../docs/specs/active/subspecs/backend-project-structure.md`](../../docs/specs/active/subspecs/backend-project-structure.md)
