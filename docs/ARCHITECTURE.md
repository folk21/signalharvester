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

The constrained `common` shared kernel may contain only stable JDK-level primitives/utilities with real cross-module reuse. It does not own functional-module contracts or business semantics; shared polling lifecycle is limited to generic demand, scheduling, and cancellation mechanics while owning modules retain cursor, query, and transport behavior.

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

Runtime SQL execution uses Micronaut-managed Jdbi rather than repository-owned JDBC statement lifecycle management. Application use cases continue to own transaction boundaries through the existing Micronaut transaction infrastructure; persistence adapters participate in those transactions and must not introduce independent business transactions. Lock-sensitive multi-statement sequences that depend on one PostgreSQL session execute on one transaction-bound Jdbi handle.

Substantial SQL remains explicit in module-owned classpath `.sql` resources and uses named bindings. The shared `common` `SqlResources` utility only resolves those resources; it does not own module SQL or business persistence semantics. SQL templating is reserved for trusted structural variation where static SQL would be less clear or planner-friendly; request values remain bind parameters. Focused Jdbi row mappers may use JDBC value types such as `ResultSet`, `Array`, or `Timestamp` for explicit conversion, but production adapters do not own `Connection`, `Statement`, or `PreparedStatement` lifecycle.

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

Analysis uses an explicit listener-completion/offset boundary:

- deterministic transport or mapping failures skip retry;
- application/database failures use bounded retry;
- the listener completes normally only after the Analysis transaction commits, or after an exhausted/invalid input is durably published to the Analysis DLQ;
- Micronaut `SYNC_PER_RECORD` commits the completed source record afterward;
- DLQ publication failure escapes before successful listener completion; framework-level commit failure is outside Analysis application retry/DLQ classification and may cause normal at-least-once redelivery.

Terminal Analysis publication uses a module-owned transactional outbox:

- dispatch claims use short expiring PostgreSQL leases;
- Kafka acknowledgement happens outside a JDBC transaction;
- success/failure metadata is written afterward;
- lifecycle interruption escapes instead of becoming ordinary retry metadata and stops the current dispatcher batch;
- a post-ack marker failure may republish the same stable event ID and payload.

Downstream consumers therefore remain idempotent. The design does not claim distributed exactly-once transactions.

## Results persistence and read boundary

Results consumes versioned terminal Analysis events asynchronously. It never reads Analysis tables or implementation types.

The analyzed-result identity is `(monitoringProfileId, normalizedItemId)`. Rejection retry identity is the upstream `sourceEventId`.

Results application services own their database transactions and processing/DLQ semantics, while Micronaut owns synchronous per-record offset commit:

- valid records use bounded retry around projection;
- deterministic transport/mapping failures bypass retry;
- the listener completes normally only after durable projection or acknowledged Results DLQ publication;
- Micronaut `SYNC_PER_RECORD` commits the completed source record afterward;
- failed DLQ publication escapes before successful completion, while framework commit failure may cause normal at-least-once redelivery without re-entering Results retry/DLQ classification.

Idempotent projection keys absorb normal at-least-once redelivery, including deliberate Analysis outbox republish after a post-ack dispatcher failure.

Results also owns the public read-only `/api/v1/results` boundary:

- list queries use deterministic newest-first keyset order and bounded page sizes;
- opaque REST page cursors are criteria-bound and separate from SSE resume cursors;
- supported product filters and text search are applied in Results persistence/query logic;
- list reads avoid per-result N+1 database round trips;
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

The consumer uses the same bounded retry and permanent-input split as other Kafka consumers. It completes normally only after successful recording or acknowledged Event Observation DLQ publication, after which Micronaut `SYNC_PER_RECORD` performs the synchronous source-offset commit.

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

Analysis, Results, and Event Observation keep application retry and terminal DLQ classification inside their listeners while Micronaut Kafka owns synchronous per-record source-offset commits through `OffsetStrategy.SYNC_PER_RECORD`.

Failure handling is deterministic:

- decode, key, and mapping failures go directly to the owning consumer's DLQ;
- application failures retry within a small configured bound;
- an interrupted listener thread escapes application retry/DLQ classification while preserving the interrupt flag;
- any exception that escapes a listener rewinds every partition represented by the failed Kafka poll to its first polled offset before normal consumption resumes;
- failed-poll rewind may deliberately duplicate records already completed earlier in that poll, which module idempotency must absorb;
- retry exhaustion produces a deterministic dead-letter identity and preserves the original key/payload, source position, consumer identity, failure details, attempt count, and retryability classification.

A listener returns normally only after durable application processing succeeds or acknowledged owner-specific DLQ publication completes. Micronaut then commits that completed record synchronously. Offset-commit mechanics do not re-enter SignalHarvester application retry or DLQ classification.

If DLQ publication fails, the listener throws before successful completion and failed-poll rewind keeps uninvoked records from that poll eligible for at-least-once processing. A framework-level commit failure after normal listener completion may likewise cause later at-least-once redelivery.

Automatic/bulk DLQ replay is deliberately absent. The accepted controlled recovery boundary addresses one real owner-specific DLQ position at a time, validates dead-letter identity plus consumer/group/topic ownership, and reuses the owning module's normal decoder/application path. It does not republish shared source topics or rewrite consumer offsets.

## HTTP server execution boundary

Micronaut Netty event-loop threads must not run blocking application work.

REST controllers that invoke database work, blocking HTTP, or other imperative blocking workflows use `@ExecuteOn(TaskExecutors.BLOCKING)` or an equivalent explicit blocking boundary. On Java 21 this uses Virtual Threads.

True streaming endpoints such as SSE keep their `Publisher`/reactive execution model. Do not move them to blocking execution mechanically. When a stream offloads bounded blocking polling, subscription cancellation should release future scheduled work and request cancellation of the active poll task when a cancellable executor handle is available.

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

The default local environment remains an explicitly trusted compatibility mode.

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

Backend-owned Kubernetes and infrastructure-observability acceptance are complete in this repository. Full product deployment acceptance spans the separately owned frontend deliverable and must be evaluated across repository boundaries.

Application Event Explorer and Processing Flow explain retained domain/event evidence. Infrastructure telemetry explains aggregate runtime health and distributed timing. These views complement each other.

`SCALABILITY.KAFKA_CONSUMERS` is accepted on the existing modular-monolith Deployment. Live verification demonstrated three Analysis consumer-group members owning the three raw-event partitions while backlog drained to zero.

## Future extraction

A module becomes a separately deployed service only for a concrete reason, such as:

- independent scaling;
- failure isolation;
- a stronger security boundary;
- independent release ownership;
- materially different resource requirements.
