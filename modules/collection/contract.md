---
type: Module Contract
title: SignalHarvester module contract — Collection
description: Public integration surface, ownership, invariants, and dependency rules for the collection module.
---
# SignalHarvester module contract — Collection

## Purpose

Own collection-run orchestration, bounded external-source access, and publication of discovered raw-item events.

## Owned responsibilities

- explicit profile-driven collection-run lifecycle;
- interval scheduling with collection-owned cluster-safe lease state;
- external source fetching and bounded concurrency;
- collection-owned source extraction, including bounded RSS/Atom parsing, REST/JSON Pointer extraction, and HTML CSS-selector extraction;
- bounded persisted-source diagnostic testing through the same fetch/extraction ports used by collection runs;
- raw-item identity generation;
- `RawItemDiscovered` publication;
- durable operational collection-run history;
- manual collection-run and diagnostic source-test REST implementations.

## Public integration surface

### Synchronous Java API

No synchronous cross-module Java API is currently published by Collection.

`CollectionRunner`, `CollectionRunHistory`, their run/result records, and related status types live under `collection.run` as internal application boundaries used by collection-owned adapters and tests. They are interfaces/models for clean internal composition, not permission for another functional module to depend on Collection synchronously.

Internal interfaces such as `ExternalSourceClient`, `RawItemEventPublisher`, and `CollectionRunHistoryStore` are replaceable implementation ports and are likewise not published module APIs.

### REST API

Authoritative schema: `contracts/api-contracts/`.

Implementation adapter: `src/main/java/io/signalharvester/collection/http/`.

Collection HTTP adapters depend on internal application boundaries such as `CollectionRunner`, `CollectionRunHistory`, and `SourceTester`; controller classes and those internal ports are not cross-module Java APIs.

### Events

Produces versioned `RawItemDiscovered` events defined under `contracts/event-contracts/src/main/proto/`.

Generated Protobuf classes are transport contract output, not collection domain/API models.

## Owned data

- PostgreSQL schema `collection`;
- durable collection-run and ordered source-outcome history;
- monitoring-profile schedule due/lease state.

## Dependencies

Synchronous functional-module dependency:

- `configuration.api..` for persisted monitoring profiles, effective source configuration, and stable profile/source identity types used internally by Collection. The Gradle dependency is an `implementation` dependency because Collection currently publishes no Java API of its own.

Asynchronous downstream processing uses Kafka event contracts rather than direct calls to analysis/results.

## Forbidden access

Collection must not access configuration-owned tables or configuration implementation packages.

Other functional modules must not depend on collection `run`, `source`, `sourcetest`, `event`, `persistence`, `configuration`, or `http` packages. Internal application ports are not cross-module APIs merely because they are Java interfaces.

## Important invariants

- a run derives information category, ordered source membership, and effective Analysis settings from the persisted monitoring profile; callers cannot override them;
- every newly published `RawItemDiscovered` carries the effective profile Analysis settings snapshot used by that run;
- automatic scheduling considers only enabled profiles and fetches only enabled member sources;
- scheduler due-work claims and lease updates use short collection-owned PostgreSQL transactions; external HTTP/Kafka work runs outside those transactions;
- source/item-level failures are best-effort and do not cancel unrelated source work;
- one collection run id is reused as correlation id for raw-item events from that run;
- raw-item identity is deterministic for equivalent source content;
- external I/O has explicit timeout, size, redirect, and concurrency bounds; runtime destination authorization is bound to the Netty connection resolver so secure mode rejects blocked or mixed address sets before connection and revalidates redirect destinations; RSS/Atom and generic extraction have explicit item-count bounds, and RSS/Atom disables DTD/external-entity processing;
- source testing uses the normal source fetch/extraction ports but never publishes Kafka events or creates collection-run history;
- REST/HTML sources retain passthrough behavior when their respective `json.*` / `html.*` extraction settings are absent;
- completed fetch payloads are terminally handled with backpressure, so raw response bodies are retained only within the bounded in-flight concurrency window rather than for the full run;
- terminal run outcomes preserve configured-source order and per-source item order, while Kafka publication follows fetch completion and must not be treated as a global source-order guarantee;
- completed run history is operational state, not an atomic substitute for Kafka delivery guarantees;
- run-history reads and writes execute inside short application-owned database transactions; Jdbi persistence adapters require an active transaction and never self-commit.

## Extension points

Source adapters, publishers, persistence stores, and parsing strategies remain internal extension seams unless a real cross-module consumer requires a published API.

Create `collection.api` only when a real functional-module consumer requires a deliberate synchronous contract; do not promote HTTP-facing application ports there pre-emptively.
