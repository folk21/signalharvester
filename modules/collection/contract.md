---
type: Module Contract
title: SignalHarvester module contract — Collection
description: Public integration surface, ownership, invariants, and dependency rules for the collection module.
---
# SignalHarvester module contract — Collection

## Purpose

Own collection-run orchestration, bounded external-source access, and publication of discovered raw-item events.

## Owned responsibilities

- explicit collection-run lifecycle;
- external source fetching and bounded concurrency;
- raw-item identity generation;
- `RawItemDiscovered` publication;
- durable operational collection-run history;
- manual collection-run REST implementation.

## Public integration surface

### Synchronous Java API

Authoritative package:

`src/main/java/io/signalharvester/collection/api/`

Primary interfaces:

- `CollectionRunner` — executes one explicit collection run;
- `CollectionRunHistory` — reads durable completed-run history.

The API package also owns the run contract data required by those interfaces:

- `CollectionRunRequest`;
- `CollectionRunResult`;
- `CollectionRunStatus`;
- `CollectionSourceResult`;
- `CollectionSourceStatus`.

Consumers should read the Java files above instead of duplicated signatures in this document.

Internal interfaces such as `ExternalSourceClient`, `RawItemEventPublisher`, and `CollectionRunHistoryStore` are replaceable implementation ports, not published module APIs.

### REST API

Authoritative schema: `contracts/api-contracts/`.

Implementation adapter: `src/main/java/io/signalharvester/collection/http/`.

The controller depends on `CollectionRunner` and `CollectionRunHistory`; controller classes themselves are not Java module APIs.

### Events

Produces versioned `RawItemDiscovered` events defined under `contracts/event-contracts/src/main/proto/`.

Generated Protobuf classes are transport contract output, not collection domain/API models.

## Owned data

- PostgreSQL schema `collection`;
- durable collection-run and ordered source-outcome history.

## Dependencies

Synchronous functional-module dependency:

- `configuration.api..` for effective enabled-source configuration and the shared stable `SourceId` contract type. Because `CollectionSourceResult` exposes `SourceId`, the Gradle dependency is intentionally an `api` dependency.

Asynchronous downstream processing uses Kafka event contracts rather than direct calls to analysis/results.

## Forbidden access

Collection must not access configuration-owned tables or configuration implementation packages.

Consumers must not depend on collection `run`, `source`, `event`, `persistence`, `configuration`, or `http` implementation packages. Internal ports are not cross-module APIs merely because they are Java interfaces.

## Important invariants

- source-level failures are best-effort and do not cancel unrelated source work;
- one collection run id is reused as correlation id for raw-item events from that run;
- raw-item identity is deterministic for equivalent source content;
- external I/O has explicit timeout, size, redirect, and concurrency bounds;
- completed run history is operational state, not an atomic substitute for Kafka delivery guarantees;
- run-history reads and writes execute inside short application-owned JDBC transactions; persistence adapters require an active transaction and never self-commit.

## Extension points

Source adapters, publishers, persistence stores, and parsing strategies remain internal extension seams unless a real cross-module consumer requires a published API.

New public synchronous operations belong under `api/` only when they are deliberate module entry points.
