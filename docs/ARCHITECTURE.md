---
type: Architecture
title: SignalHarvester architecture
description: Stable backend system boundaries, module ownership, communication contracts, persistence boundaries, and deployment direction.
---
# SignalHarvester architecture

## Purpose

This document owns stable accepted architecture.

Active specifications describe intended unresolved changes. They may temporarily contain more detail while work is in progress.

## Core architectural decision

SignalHarvester starts as a modular monolith: one deployable Micronaut backend assembled from cohesive Gradle modules.

The module boundaries are designed to preserve a future service-extraction path. Distributed deployment is not the default.

## Module ownership

Functional modules own complete capabilities instead of repository-wide technical layers.

The initial module set is:

- configuration;
- collection;
- analysis;
- results;
- event observation;
- security.

Each module owns its own:

- model and use cases;
- persistence;
- adapters;
- tests;
- deliberately published public API.

A module does not need a Java `api` package when it has no synchronous functional-module consumer.

When a synchronous cross-module contract is required:

- it lives under `io.signalharvester.<module>.api`;
- it exposes only the minimal interfaces and contract data needed by consumers;
- consumers may depend only on the providing module's `api..` packages.

The `api` package is not a home for every Java interface.

Controller-facing application ports, repositories, outbound clients, publishers, analyzers, and other internal ports stay internal unless they are intentionally published capabilities.

Internal implementation packages and private database tables are never cross-module APIs.

Each module root also contains a concise `contract.md`. It indexes the module's Java, OpenAPI, and event surfaces plus ownership, dependency rules, and invariants. It does not duplicate source signatures.

## Communication boundaries

Use the transport that matches the boundary:

- in-process synchronous collaboration — Java interfaces and Java types;
- asynchronous integration — Kafka + versioned Protobuf;
- external application API — REST + JSON/OpenAPI;
- live browser updates — SSE + JSON.

The browser never connects directly to Kafka or PostgreSQL.

## Persistence boundary

One PostgreSQL instance is sufficient initially. Schemas and tables remain module-owned.

Flyway migrations belong to the owning module. Direct cross-module table access is forbidden.

Use transactional outbox and idempotent-consumer patterns where event/data atomicity or duplicate delivery requires them.

## External collection boundary

Collection is configuration-driven where practical. External I/O stays behind testable boundaries.

The generic HTTP transport uses Micronaut's managed low-level HTTP client with absolute request URIs.

Its synchronous module-facing contract runs on Micronaut's blocking executor. On Java 21, that executor uses Virtual Threads, so imperative collection work does not block Netty event-loop threads.

Thread cost does not remove the need for backpressure. Collection concurrency remains explicitly bounded.

A Collection Run uses best-effort source isolation:

- failure of one source does not cancel unrelated source work;
- the explicit Collection Run ID is the correlation ID for raw-item events from that run;
- HTTP connect/read/request timeouts remain explicit;
- response-size, redirect, connection-pool, and concurrency limits remain explicit.

Client filters own only cross-cutting transport concerns. Collection adapters own response status and source-specific error interpretation, including normalization of Micronaut response exceptions.

Runtime destination authorization is enforced at the actual DNS/connection boundary by `SECURITY.EXTERNAL_SOURCE_ACCESS`.

The secure policy covers loopback, link-local, private/shared-network, mixed-DNS, and redirect destinations. An explicit trusted-local mode remains available for deterministic development fixtures.

## Analysis processing boundary

Analysis consumes versioned raw-item Kafka bytes at an adapter boundary. It immediately maps transport messages into module-owned semantic models.

Generated Protobuf classes remain transport types. Normalization, deduplication, analyzer logic, and persistence do not depend on generated messages.

The processing order is:

1. normalize the collected item;
2. derive stable logical identity;
3. apply monitoring-profile-scoped deduplication;
4. run analysis for non-duplicate items;
5. atomically persist authoritative Analysis state and the exact serialized terminal event in the outbox.

Logical item identity is stable across Collection Runs and Monitoring Profiles. An explicit source external ID is preferred. Otherwise identity is derived from normalized source URL/content plus source identity.

Duplicate acceptance is monitoring-profile scoped. The same logical item may therefore be independently relevant to different profiles.

The initial analyzer is deterministic and replaceable through `ContentAnalyzer`. External AI is not a core dependency.

Analysis uses explicit Kafka offset control:

- deterministic transport or mapping failures skip retry;
- application/database failures use bounded retry;
- source offsets advance after the Analysis transaction commits, or after an exhausted/invalid input is durably published to the Analysis DLQ;
- DLQ publication failure leaves the source offset uncommitted.

Terminal Analysis publication uses a module-owned transactional outbox:

- dispatch claims use short expiring PostgreSQL leases;
- Kafka acknowledgement happens outside a JDBC transaction;
- success/failure metadata is written afterward;
- a post-ack marker failure may republish the same stable event ID and payload.

Downstream consumers therefore remain idempotent. The design does not claim distributed exactly-once transactions.

## Results persistence and read boundary

Results consumes versioned terminal Analysis events asynchronously. It never reads Analysis tables or implementation types.

The analyzed-result identity is `(monitoringProfileId, normalizedItemId)`. Rejection retry identity is the upstream `sourceEventId`.

Results owns its JDBC transaction and explicit offset semantics:

- valid records use bounded retry around projection;
- deterministic transport/mapping failures bypass retry;
- the consumed offset advances only after durable projection or acknowledged Results DLQ publication;
- failed DLQ publication leaves the offset uncommitted.

Idempotent projection keys absorb normal at-least-once redelivery, including deliberate Analysis outbox republish after a post-ack dispatcher failure.

Results also owns the public read-only `/api/v1/results` boundary:

- list queries are bounded and newest-first;
- supported product filters are applied in Results persistence/query logic;
- list reads avoid per-result N+1 queries;
- point detail lookup is monitoring-profile scoped;
- detail returns persisted content, normalized attributes/tags, and provenance.

Other modules and browser clients do not read Results tables directly.

## Event observation boundary

Event Observation consumes selected published Kafka events through its own consumer group. It never reads another module's private tables.

Its bounded PostgreSQL projection stores:

- event/envelope identity;
- Kafka transport metadata;
- correlation and trace identifiers;
- selected decoded payload fields.

Large content bodies are intentionally excluded.

The projection is diagnostic state. It is not an authoritative replacement for Kafka or another module's domain data.

The consumer uses the same bounded retry and permanent-input split as other Kafka consumers. Source offsets advance only after successful recording or acknowledged Event Observation DLQ publication.

Retention is explicitly bounded by age and count.

Public diagnostic boundaries are:

- `/api/v1/events` for bounded REST history;
- `/api/v1/events/stream` for resumable SSE;
- Processing Flow REST views reconstructed from the same retained projection.

Durable observation IDs are SSE resume cursors across backend replicas.

Processing Flow keeps raw-to-terminal lineage by published source-event identity. It explicitly distinguishes:

- directly observed evidence;
- stages derived from current event semantics;
- stages that the current event model cannot prove.

## Kafka consumer failure boundary

Analysis, Results, and Event Observation use explicit manual offset commits plus versioned `failure/v1/DeadLetterEvent` records.

Failure handling is deterministic:

- decode, key, and mapping failures go directly to the owning consumer's DLQ;
- application failures retry within a small configured bound;
- retry exhaustion produces a deterministic dead-letter identity and preserves the original key/payload, source position, consumer identity, failure details, attempt count, and retryability classification.

DLQ acknowledgement is part of terminal durability. A source offset never advances merely because retries were exhausted.

If DLQ publication fails, normal Kafka redelivery remains the recovery path.

Automatic DLQ replay is deliberately absent. Future replay tooling must be controlled and preserve original event semantics.

## HTTP server execution boundary

Micronaut Netty event-loop threads must not run blocking application work.

REST controllers that invoke JDBC, blocking HTTP, or other imperative blocking workflows use `@ExecuteOn(TaskExecutors.BLOCKING)` or an equivalent explicit blocking boundary. On Java 21 this uses Virtual Threads.

True streaming endpoints such as SSE keep their `Publisher`/reactive execution model. Do not move them to blocking execution mechanically.

Expected application/domain failures are translated through Micronaut `ExceptionHandler` implementations at the HTTP boundary. Avoid repeated controller-local `try/catch` translation.

Handlers and filters remain non-blocking unless they explicitly offload blocking work.

## Security boundary

The Security module owns:

- persisted identities;
- local credential verification;
- baseline role invariants;
- security-owned authentication and user-administration HTTP adapters.

Its PostgreSQL tables are private to the module. Other functional modules do not query them or make authorization decisions from Security implementation types.

HTTP authorization spans APIs owned by several modules. The endpoint/role matrix therefore belongs to composition-root configuration instead of creating synchronous dependencies from every module to Security.

Roles are explicit and additive:

- `ADMIN` does not imply `VIEWER`;
- human identities receive `USER`;
- system identities may use `BOT` without human UI capability.

Browser authentication uses an HttpOnly JWT cookie so native SSE remains compatible. Cookie-authenticated mutations require explicit double-submit CSRF protection.

JWTs are short-lived and stateless. Account disablement and role changes prevent future login/issuance, but already-issued credentials remain valid until expiry unless a future revocation design is introduced.

The default local environment remains an explicitly trusted compatibility mode while the companion frontend migrates.

Shared or public deployments must enable the protected security profile and provide deployment-owned JWT/CSRF secrets.

## UI boundary

The web UI lives in the separate `signalharvester-web` repository.

This repository owns backend REST/OpenAPI and SSE contracts. The UI repository owns React/TypeScript implementation and UI-specific specifications.

## Observability and deployment

The application exposes:

- Micronaut health, liveness, and readiness endpoints;
- a Prometheus scrape endpoint;
- low-cardinality Micrometer application/runtime metrics;
- OpenTelemetry HTTP server/client, Kafka, and JDBC traces;
- stdout/stderr logs with trace/span MDC fields when a valid span is active.

Entity identifiers such as source, profile, run, item, event, and URL values are not metric labels.

Trace export is disabled by default. Runtime configuration can enable OTLP export.

Custom asynchronous boundaries preserve trace context explicitly:

- Collection captures Micronaut `PropagatedContext` before submitting source fetches;
- every Collection Run owns an application span used for event `traceparent` when no parent was supplied;
- Analysis persists active trace context with transactional-outbox rows and restores it before Kafka publication.

Telemetry is auxiliary. Disabling Micrometer or OpenTelemetry does not change business correctness.

Kubernetes is the target deployment environment. The backend-owned local Kubernetes stack and Prometheus/Loki/Tempo/Grafana observability stack are accepted after live developer verification.

Full platform Kubernetes acceptance still depends on the separately owned real frontend image.

Application Event Explorer and Processing Flow explain retained domain/event evidence. Infrastructure telemetry explains aggregate runtime health and distributed timing. These views complement each other.

The current deployment-related verification-pending work is `SCALABILITY.KAFKA_CONSUMERS`, not the Kubernetes/observability stack itself.

## Future extraction

A module becomes a separately deployed service only for a concrete reason, such as:

- independent scaling;
- failure isolation;
- a stronger security boundary;
- independent release ownership;
- materially different resource requirements.
