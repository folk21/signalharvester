---
type: Architecture
title: SignalHarvester architecture
description: Stable backend system boundaries, module ownership, communication contracts, persistence boundaries, and deployment direction.
---
# SignalHarvester architecture

## Purpose

This document owns stable accepted architecture. Active specs describe intended changes and may temporarily be more detailed while work is in progress.

## Core architectural decision

SignalHarvester starts as a modular monolith: one Micronaut backend deployable composed from cohesive Gradle modules. Module boundaries are designed so extraction into separate services is possible later without making distributed deployment the default.

## Module ownership

Functional modules own complete capabilities rather than repository-wide technical layers. The initial module set is:

- configuration;
- collection;
- analysis;
- results;
- event observation;
- security.

Each module owns its model/use cases, persistence, adapters, tests, and any public API it deliberately publishes. A module does not need a Java `api` package when it has no synchronous functional-module consumer. When a synchronous cross-module contract is required, it lives under `io.signalharvester.<module>.api` and is expressed through interfaces with only the minimal contract data they require. The `api` package is not a home for every Java interface: controller-facing application ports, repositories, outbound clients, publishers, analyzers, and other internal ports stay internal unless they are intentionally published capabilities. Cross-module synchronous access may target only the providing module's `api..` package; internal implementation packages and private database tables are not cross-module APIs. Each module root also contains a concise `contract.md` that indexes its Java/OpenAPI/event surfaces, ownership, dependency rules, and invariants without duplicating source signatures.

## Communication boundaries

- in-process synchronous collaboration: Java interfaces/types;
- asynchronous integration: Kafka + versioned Protobuf;
- external application API: REST + JSON/OpenAPI;
- live browser updates: SSE + JSON.

The browser never connects directly to Kafka or PostgreSQL.

## Persistence boundary

One PostgreSQL instance is sufficient initially, but schemas/tables remain module-owned. Flyway migrations belong to the owning module. Direct cross-module table access is forbidden.

Transactional outbox and idempotent-consumer patterns are introduced where event/data atomicity or duplicate delivery requires them.

## External collection boundary

Collection is configuration-driven where practical. External I/O remains behind testable boundaries. The generic HTTP transport uses Micronaut's managed low-level HTTP client with absolute request URIs and a synchronous module-facing contract executed on Micronaut's blocking executor. On the Java 21 baseline that executor uses Virtual Threads, which keeps collection workflows imperative without blocking Netty event-loop threads.

Collection concurrency remains explicitly bounded independently of thread cost. A collection run treats source-level fetch/publication failures as best-effort outcomes: failure of one source does not cancel unrelated source work. The explicit collection run id is the correlation id for raw-item events produced by that run. HTTP connect/read/request timeouts, response-size limits, redirect limits, and connection-pool limits remain explicit runtime configuration. Client filters own only cross-cutting transport concerns; collection adapters own status/error interpretation, including normalization of Micronaut response exceptions. Configured targets currently assume trusted application configuration; before source-management APIs are exposed to untrusted users, outbound network destination policy must address SSRF-sensitive loopback, link-local, private-network, and redirect targets without breaking deterministic local development sources.

## Analysis processing boundary

Analysis consumes versioned raw-item Kafka bytes at an adapter boundary and immediately maps them into module-owned semantic models. Generated Protobuf classes remain transport types; normalization, deduplication, analyzer logic, and persistence do not depend on generated messages.

Normalization precedes deduplication and analysis. Logical item identity is stable across collection runs and monitoring profiles: an explicit source external id is preferred, otherwise identity is derived from normalized source URL/content plus source identity. Duplicate acceptance is monitoring-profile scoped, so the same logical item may be independently relevant to different profiles. Analysis owns its durable deduplication state in PostgreSQL.

The initial analyzer is deterministic and replaceable through `ContentAnalyzer`; external AI is not a core dependency. Raw Kafka offsets are committed explicitly only after the Analysis transaction commits its deduplication state together with the serialized terminal-event outbox row, or after an exhausted/invalid input is durably published to the Analysis dead-letter topic. Deterministically invalid transport/mapping inputs skip retry; application/database failures use bounded retry. DLQ publication failure leaves the source offset uncommitted. Terminal Analysis Kafka publication is decoupled through the module-owned transactional outbox: dispatch claims use short expiring PostgreSQL leases, Kafka acknowledgement occurs outside a JDBC transaction, and success/failure metadata is written afterward. A post-ack marker failure may republish the same stable event id/payload, so downstream consumers remain idempotent without requiring distributed exactly-once transactions.

## Results persistence and read boundary

Results consumes versioned terminal Analysis events asynchronously and never reads Analysis tables or implementation types. The materialized analyzed-result identity is `(monitoringProfileId, normalizedItemId)`; rejection retry identity is the upstream `sourceEventId`. Results owns its JDBC transaction. Valid records use bounded retry around projection; deterministic transport/mapping failures bypass retry. The consumed offset advances only after durable projection or acknowledged Results dead-letter publication, and a failed DLQ publication leaves it uncommitted. Idempotent projection keys absorb normal at-least-once redelivery, including deliberate outbox republish after a post-ack dispatcher failure, without claiming distributed exactly-once semantics.

Results also owns the public read-only `/api/v1/results` REST boundary. Feed queries are bounded, newest-first, filterable by product dimensions, and avoid per-result N+1 reads; point detail lookup is monitoring-profile scoped and returns the persisted content, normalized attributes/tags, and provenance. Other modules and browser clients do not read Results tables directly.

## Event observation boundary

Event Observation consumes selected published Kafka events through a consumer group that is independent from business consumers. It never reads another module's private tables. The module materializes event/envelope identity, Kafka transport metadata, correlation/trace identifiers, and selected decoded payload fields into its own bounded PostgreSQL schema. Large content bodies are intentionally excluded.

The observation projection is diagnostic state, not an authoritative replacement for Kafka or another module's domain data. Its consumer applies the same bounded retry/permanent-input split and advances source offsets only after successful recording or acknowledged Event Observation dead-letter publication. Retention is bounded explicitly by age and count. The public `/api/v1/events` REST history and `/api/v1/events/stream` SSE stream expose JSON suitable for the browser; the browser never decodes Protobuf or connects directly to Kafka. Durable observation ids serve as SSE resume cursors across backend replicas. Processing-flow REST views are reconstructed on read from the same bounded projection. They keep raw-to-terminal lineage by published source-event identity and explicitly distinguish observed evidence, derived stages, and stages that the current event model cannot prove.

## Kafka consumer failure boundary

Analysis, Results, and Event Observation use explicit manual offset commits together with versioned `failure/v1/DeadLetterEvent` records. Decode/key/mapping failures are deterministic for the same input bytes and therefore go directly to the owning consumer's DLQ. Application failures retry within a small configured bound. After retry exhaustion, a deterministic dead-letter identity plus the original key/payload, source topic/partition/offset, consumer identity, failure type/message, attempt count, and retryability classification are published to the owning DLQ.

DLQ acknowledgement is part of terminal durability: a source offset is never advanced merely because retries were exhausted. If the DLQ cannot be published, normal Kafka redelivery remains the recovery path. Automatic DLQ replay is deliberately absent; later replay tooling must be controlled and preserve original event semantics.

## HTTP server execution boundary

Micronaut Netty event-loop threads must not run blocking application work. REST controller methods or classes that invoke JDBC, blocking HTTP, or other imperative blocking workflows use `@ExecuteOn(TaskExecutors.BLOCKING)`. On the Java 21 baseline this means Virtual Threads. True streaming endpoints such as SSE keep their `Publisher`/reactive execution model and are not moved to blocking execution mechanically. Expected API/domain failures should be translated at the HTTP boundary through Micronaut `ExceptionHandler` implementations rather than repeated controller `try/catch` blocks; handlers and filters remain non-blocking unless explicitly offloaded.

## Security boundary

The security functional module owns persisted identities, local credential verification, baseline role invariants, and security-owned authentication/user-administration HTTP adapters. Its PostgreSQL tables are private to that module. Other functional modules do not query security tables or make authorization decisions from security implementation types.

HTTP authorization spans APIs owned by several modules, so the endpoint/role matrix is composition-root configuration rather than a synchronous dependency from every module to Security. Roles are explicit and additive: `ADMIN` does not imply `VIEWER`, human identities receive `USER`, and system identities may use `BOT` without human UI capability. Browser JWT transport uses an HttpOnly cookie so native SSE remains compatible; cookie-authenticated mutations use explicit CSRF protection. JWTs are short-lived and stateless, so account disablement and role changes prevent future login/issuance while already-issued credentials remain valid until expiry unless a later revocation design is introduced.

The default local environment remains an explicitly trusted compatibility mode while the companion frontend migrates. Shared/public deployments must enable the protected security profile and provide deployment-owned signing/CSRF secrets.

## UI boundary

The web UI lives in the separate `signalharvester-web` repository. This repository owns backend REST/OpenAPI and SSE contracts; the UI repository owns React/TypeScript implementation and UI-specific specifications.

## Observability and deployment

The application exposes Micronaut health, liveness, and readiness endpoints plus a Prometheus scrape endpoint. Micrometer owns low-cardinality runtime/application metrics; entity identifiers such as source, profile, run, item, event, and URL values are not metric labels.

OpenTelemetry is the distributed-tracing standard. The assembled application instruments HTTP server/client, Kafka, and JDBC boundaries. Trace export is disabled by default and can be enabled for an OTLP collector through runtime configuration. Console logs remain stdout/stderr oriented and include OpenTelemetry trace/span MDC fields when a valid span is current.

Custom asynchronous boundaries preserve context explicitly. Collection captures Micronaut `PropagatedContext` before submitting source fetches to the blocking executor, and every collection run owns an application span used for the W3C `traceparent` carried by raw-item events when no parent was supplied. Analysis persists the active trace context with each transactional-outbox row and restores that context before Kafka publication, so broker retries do not sever the logical trace. Telemetry is auxiliary: disabling Micrometer/OpenTelemetry does not change business correctness.

Kubernetes is the target deployment environment. Prometheus, Loki, Tempo, and Grafana remain the planned infrastructure observability stack. Application Event Explorer and Processing Flow explain retained domain/event evidence; infrastructure telemetry explains runtime health and distributed timing. They complement rather than replace each other.

## Future extraction

A module becomes a separately deployed service only for a concrete reason such as independent scaling, failure isolation, security boundary, independent release ownership, or materially different resource requirements.
