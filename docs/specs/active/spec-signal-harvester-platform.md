---
type: Specification
title: SignalHarvester initial functional product specification
description: Active umbrella specification for an observable event-driven platform that collects, analyzes, stores, and presents configurable external information streams.
document_role: umbrella
spec_status: active
current_focus: subspecs/backend-capacity-observability-baseline.md
---
# SignalHarvester initial functional product specification

## Status

Active umbrella specification for the initial SignalHarvester product target.

The accepted backend baseline includes:

- configuration and collection;
- Analysis and Results;
- Event Observation and Processing Flow;
- bounded Kafka retry/DLQ handling;
- `ANALYSIS.OUTBOX`;
- `OBSERVABILITY.APPLICATION`;
- backend authentication/RBAC;
- `SECURITY.EXTERNAL_SOURCE_ACCESS`;
- backend-owned Kubernetes/infrastructure observability;
- controlled live system resilience acceptance;
- accepted `SCALABILITY.KAFKA_CONSUMERS` horizontal worker scaling.

`backend-profile-owned-analysis-settings`, `backend-results-production-browsing`, `backend-controlled-dead-letter-recovery`, the repository-wide `backend-jdbi-persistence-refactoring`, `backend-scheduler-pre-run-lease-recovery`, `backend-analysis-outbox-lease-renewal`, `backend-kafka-offset-commit-failure-separation`, `backend-module-contract-discoverability`, `backend-scheduler-expired-lease-fencing`, `backend-kafka-listener-interruption-fencing`, `backend-kafka-listener-failed-poll-rewind`, `backend-analysis-outbox-interruption-fencing`, `backend-sse-inflight-poll-cancellation`, `backend-shared-polling-lifecycle-refactoring`, `backend-collection-run-interruption-recovery`, `backend-source-fetch-worker-interruption-propagation`, `backend-analysis-outbox-expired-lease-fencing`, and `backend-analysis-outbox-failure-lease-fencing` are accepted after their relevant repository tests and validations passed. Kafka consumer lifecycle Review I, background-worker/executor lifecycle Review II, and durable-ownership/crash-window Review III found no additional material correctness defects after their bounded fixes. `backend-analysis-all-relevant-default` remains verification-pending. The current bounded implementation focus is `backend-capacity-observability-baseline`, which measures a deterministic local Kubernetes pipeline workload before any capacity optimization or ML-based operational analysis.

Detailed frontend implementation and frontend-image lifecycle remain owned by the `signalharvester-web` specification tree. Acceptance of umbrella requirements that span both deliverables must be evaluated across repository boundaries; this backend specification does not duplicate the companion repository's current implementation inventory.

Stable feature identifiers referenced by this specification are defined in [`../../FEATURES.md`](../../FEATURES.md).

## Goal

Deliver an observable event-driven information collection and analysis platform that can:

- let a user define what information should be collected and from which external sources;
- periodically query configured external sources without requiring code changes for every newly added compatible source;
- collect configurable external information such as scientific data, news/topic information, and financial data;
- move collected items through an asynchronous processing pipeline;
- normalize, deduplicate, analyze, score, classify, and persist collected information;
- present newly discovered and analyzed information in a browser UI that updates automatically without manual page refresh;
- separate expert/administrative workflows from the consumer-facing result experience through explicit authenticated roles and backend authorization;
- make the movement of events through the system visually inspectable;
- make important persistence and processing outcomes visible without exposing the frontend directly to Kafka or PostgreSQL protocols;
- provide production-style technical observability through metrics, logs, distributed traces, and health information;
- run locally in Kubernetes as a realistic distributed-system learning environment.

The project is intentionally both a useful application and a learning platform for Java concurrency, Kafka, PostgreSQL, Kubernetes, event-driven design, reliability patterns, observability, AI-assisted development, and Spec-Driven Development.

## Product concept

SignalHarvester collects external information according to user-defined monitoring profiles.

A monitoring profile describes a topic or data stream of interest, for example:

- `Recent climate-research publications`;
- `AI-assisted software development news`;
- `Central-bank policy announcements`;
- `Public-company earnings releases`.

A profile references one or more configured sources and defines collection frequency and optional search, filtering, and analysis rules.

The initial product demonstrates configurable monitoring across representative information domains such as:

1. **Scientific information** — publications, datasets, announcements, or other research-oriented material exposed by configured sources.
2. **News and topic information** — news, articles, posts, release notes, or other externally published information relevant to configured topics.
3. **Financial information** — market, company, regulatory, or other finance-oriented data exposed by configured sources.

These are representative examples rather than hardcoded domain limits. The internal processing model must remain generic enough to support additional information categories without redesigning the pipeline.

## System context

The system has four primary functional areas:

1. **Configuration** — stores monitoring profiles, sources, schedules, extraction settings, filters, and analysis settings.
2. **Collection** — periodically discovers due collection work, accesses external sources, and emits discovered information into the event pipeline.
3. **Analysis and persistence** — normalizes, deduplicates, analyzes, classifies, scores, and stores collected information.
4. **Presentation and observation** — exposes configuration, collected results, live updates, event history, and processing-flow visualization to the user.

Kafka is the main asynchronous transport between processing stages.

PostgreSQL is the authoritative application data store for configuration, collected domain data, processing state, and selected event-observation data where persistence is required.

The browser communicates with application APIs over HTTP-based application protocols. The browser must not connect directly to Kafka or PostgreSQL.

## Current state

This umbrella describes the product target, not the complete implementation inventory. Current implementation truth belongs in `docs/IMPLEMENTATION.md`.

Accepted backend capabilities already cover the main functional pipeline:

- persisted Sources and Monitoring Profiles;
- manual and scheduled Collection Runs;
- Kafka/Protobuf event processing;
- normalization, deduplication, and deterministic analysis;
- Results persistence, REST reads, and resumable SSE;
- Event Observation and Processing Flow diagnostics;
- bounded Kafka retry and dead-letter handling;
- Analysis transactional outbox delivery.

Backend-owned `DEPLOYMENT.KUBERNETES`, `OBSERVABILITY.INFRASTRUCTURE`, and the controlled system resilience workflow are accepted. Requirements whose final acceptance depends on both backend and frontend deliverables remain cross-repository acceptance concerns; the companion repository owns its own implementation status and evidence.

The intended technology direction remains:

- Java/Micronaut;
- Kafka and Protocol Buffers;
- PostgreSQL;
- React/TypeScript;
- REST/SSE;
- Kubernetes;
- OpenTelemetry, Prometheus, Loki, Tempo, and Grafana.

Detailed framework configuration belongs to bounded technical specifications and current-state documentation.

## Requirement map

This table is a navigation index. The detailed requirement text below remains normative.

| Requirement | Feature ID | Purpose |
|---|---|---|
| R1 | `CONFIGURATION.MONITORING_PROFILES` | Persisted monitoring profiles |
| R2 | `CONFIGURATION.SOURCES` | Persisted external sources |
| R3 | `COLLECTION.SOURCE_TEST` | Source validation and preview |
| R4 | `COLLECTION.SCHEDULING` | Scheduled collection |
| R5 | `COLLECTION.RUNS` | Explicit collection-run identity |
| R6 | `COLLECTION.ADAPTERS` | External-source adapter boundary |
| R7 | `EVENTING.PIPELINE`, `CONTRACTS.KAFKA_PROTOBUF` | Asynchronous Kafka processing |
| R8 | `EVENTING.CORRELATION` | Event/run/item/trace correlation |
| R9 | `ANALYSIS.NORMALIZATION` | Normalized content model |
| R10 | `ANALYSIS.DEDUPLICATION` | Deterministic duplicate handling |
| R11 | `ANALYSIS.CLASSIFICATION` | Replaceable analysis boundary |
| R12 | `RESULTS.MATERIALIZATION` | Persisted analyzed results |
| R13 | `RESULTS.LIVE` | Live result feed |
| R14 | `PRESENTATION.CONFIGURATION` | Browser configuration workflows |
| R15 | `RESULTS.BROWSING` | Result browsing and inspection |
| R16 | `DIAGNOSTICS.EVENT_OBSERVATION` | Live technical event explorer |
| R17 | `DIAGNOSTICS.PROCESSING_FLOW` | Visual processing-flow inspection |
| R18 | `OBSERVABILITY.APPLICATION` | Application telemetry and health |
| R19 | `OBSERVABILITY.INFRASTRUCTURE` | Infrastructure telemetry views |
| R20 | `RELIABILITY.KAFKA_RETRY`, `RELIABILITY.DEAD_LETTER` | Visible bounded failure handling |
| R21 | `RELIABILITY.IDEMPOTENCY` | Duplicate-safe processing |
| R22 | `ANALYSIS.OUTBOX` | Database/event consistency |
| R23 | `RUNTIME.CONCURRENCY`, `SECURITY.EXTERNAL_SOURCE_ACCESS` | Bounded blocking/external I/O |
| R24 | `DEPLOYMENT.KUBERNETES` | Local Kubernetes deployment |
| R25 | `DELIVERY.FRONTEND_BACKEND_BOUNDARY`, `CONTRACTS.HTTP` | Independent deliverables and explicit HTTP contracts |
| R26 | `SCALABILITY.KAFKA_CONSUMERS` | Horizontal worker scaling |
| R27 | `DATA.PROVENANCE` | Source/run provenance |
| R28 | `DIAGNOSTICS.EVENT_OBSERVATION` | Bounded diagnostic retention |
| R29 | `TESTING.DETERMINISTIC_LOCAL` | Deterministic local verification |
| R30 | `SECURITY.EXTERNAL_SOURCE_ACCESS` | Secret and external-service safety |
| R31 | `SECURITY.IDENTITY_ROLES`, `SECURITY.AUTHENTICATION`, `SECURITY.AUTHORIZATION` | Authenticated identities and RBAC |
| R32 | `PRESENTATION.VIEWER_RESULTS`, `DIAGNOSTICS.ANALYSIS_INSPECTION`, `SECURITY.AUTHORIZATION` | Role-specific browser experience |

## Requirements

### R1 — configurable monitoring profiles

Feature: `CONFIGURATION.MONITORING_PROFILES`.

The user must be able to create, edit, enable, disable, and remove monitoring profiles through the web UI.

A monitoring profile must have stable identity and must define at least:

- a human-readable name;
- an information category;
- enabled/disabled state;
- one or more associated sources;
- a collection schedule or interval;
- search or matching criteria appropriate to its category.

Configuration must be persisted in PostgreSQL and survive application restarts.

### R2 — configurable external sources

Feature: `CONFIGURATION.SOURCES`.

The user must be able to manage external sources through the web UI.

A source must have stable identity and must describe at least:

- a human-readable name;
- source type;
- source location or endpoint;
- enabled/disabled state;
- source-specific configuration required to collect and interpret data.

The design must support source types with reusable collectors, such as REST, RSS/Atom, and configurable HTML extraction.

Adding a new source that is compatible with an existing collector type should not require a new application deployment.

A source that requires genuinely source-specific behavior may use a dedicated adapter, but this must be an explicit extension rather than the default model.

### R3 — source validation before activation

Feature: `COLLECTION.SOURCE_TEST`.

The UI should allow a configured source to be tested before or during activation.

A source test should make the intended external request using the same collection boundary used by normal execution and report enough information to diagnose configuration problems, including where applicable:

- request success or failure;
- HTTP status;
- response or parsing duration;
- number of candidate items detected;
- a bounded preview of extracted items;
- parsing or validation errors.

Testing a source must not silently insert test results into the normal production result feed unless explicitly requested.

### R4 — schedule-driven collection

Feature: `COLLECTION.SCHEDULING`.

Enabled monitoring profiles must be collected automatically according to their configured schedules.

A user must not need to manually trigger each collection cycle.

When a new compatible source is added to an enabled profile, subsequent scheduled runs must automatically include that source without requiring application restart or redeployment.

The scheduler must be designed so that multiple backend replicas do not unintentionally execute the same scheduled collection work as independent duplicates.

### R5 — explicit collection runs

Feature: `COLLECTION.RUNS`.

Every scheduled or manually triggered collection operation must create an explicit collection-run identity.

A collection run must record enough state to determine at least:

- what profile triggered it;
- when it started and finished;
- which sources were requested;
- execution status;
- aggregate success/failure information.

Events and collected items produced by that run must be traceable back to it.

### R6 — external data enters through collection adapters

Feature: `COLLECTION.ADAPTERS`.

External data must enter the application through explicit collection adapters rather than being mixed directly into analysis or persistence code.

Collection adapters must isolate external protocol, parsing, and source-specific concerns from the core processing model.

The initial implementation must support at least:

- one real structured or semi-structured external data source;
- one real external topic/news information source.

Synthetic sources may be used for deterministic tests and demonstrations but do not satisfy this requirement by themselves.

### R7 — asynchronous event-driven processing

Feature: `EVENTING.PIPELINE`, `CONTRACTS.KAFKA_PROTOBUF`.

Collected information must move between major processing stages through Kafka-backed asynchronous events where asynchronous decoupling is useful.

Kafka integration-event payloads must use Protocol Buffers contracts defined from versioned `.proto` schemas.

The product must visibly demonstrate an event-driven processing path rather than using Kafka only as an incidental dependency.

The first functional pipeline should include conceptually equivalent stages for:

- collection request or collection execution;
- raw item discovery;
- normalization;
- deduplication;
- analysis;
- persistence or materialized result availability.

The exact number of physical Kafka topics and services is a technical design decision and must not be inferred directly from the number of conceptual stages.

### R8 — stable event identity and correlation

Feature: `EVENTING.CORRELATION`.

Important application events must have stable event identity.

Events belonging to one logical collection or item-processing flow must carry correlation information sufficient to reconstruct that flow across service and Kafka boundaries.

At minimum the system must be able to correlate:

- a collection run;
- a discovered external item;
- the processing events derived from that item;
- the persisted result when one is produced.

Correlation must integrate cleanly with distributed tracing rather than creating an unrelated parallel tracing model.

### R9 — normalized common content model

Feature: `ANALYSIS.NORMALIZATION`.

Externally collected records must be converted into an internal normalized representation before downstream analysis depends on them.

The common model must preserve source provenance and permit category-specific attributes without forcing all categories into a single flat schema.

For every persisted content item, the system must be able to identify its originating source and external reference or URL when available.

### R10 — deterministic deduplication boundary

Feature: `ANALYSIS.DEDUPLICATION`.

The system must detect repeated discovery of the same logical external item and must not create uncontrolled duplicate domain records merely because the item was collected again.

Deduplication must use explicit stable source identifiers where available and may use deterministic fingerprints when no suitable external identifier exists.

Deduplication behavior must be observable so that a user or developer can distinguish a newly accepted item from an ignored duplicate.

### R11 — pluggable analysis

Feature: `ANALYSIS.CLASSIFICATION`.

Analysis must be represented by a replaceable application boundary rather than being hardwired to one algorithm or AI provider.

The initial product must support deterministic rule-based analysis sufficient to classify or score content without requiring an external LLM.

The architecture must permit later analysis adapters such as:

- LLM-based classification or summarization;
- embedding-based similarity;
- domain-specific scoring;
- heuristic or rule-based filters.

Analysis results must preserve enough explanation to show why an item was considered relevant or irrelevant where the analyzer can provide such information.

### R12 — persisted results

Feature: `RESULTS.MATERIALIZATION`.

Normalized and accepted domain results must be persisted in PostgreSQL.

Persisted results must support efficient retrieval by the frontend for at least:

- latest items;
- information category;
- monitoring profile;
- source;
- time range;
- relevance or analysis outcome.

The first implementation does not require a general-purpose search engine such as Elasticsearch.

### R13 — live result feed

Feature: `RESULTS.LIVE`.

The web UI must provide a result feed for newly collected information.

When new results become available, an already-open page must update automatically without requiring manual refresh.

The initial live-update transport should support efficient server-to-browser delivery and reconnection.

Server-Sent Events are the preferred initial mechanism. A technical sub-spec may choose bidirectional WebSocket communication only when it demonstrates a concrete need.

The live feed must not require the browser to connect directly to Kafka.

### R14 — configuration UI

Feature: `PRESENTATION.CONFIGURATION`.

The web application must provide usable screens for configuring:

- monitoring profiles;
- sources;
- schedules;
- search criteria;
- available analysis settings required by the first product slice.

The UI must validate input before submission where validation rules are known to the client, while the backend remains authoritative for business validation.

Configuration changes must become visible to collection scheduling without requiring deployment or process restart.

### R15 — result browsing and inspection

Feature: `RESULTS.BROWSING`.

The web application must let the user browse and inspect collected results.

For monitored external information, the initial view should expose at least:

- title or primary label;
- source;
- publication/discovery time when available;
- summary or extracted description when available;
- relevant category-specific attributes when available;
- extracted tags or topics when available;
- analysis score or classification when configured;
- link to the original source when available.

### R16 — live technical event explorer

Feature: `DIAGNOSTICS.EVENT_OBSERVATION`.

The web application must provide a technical event-explorer view that can display application processing events as they occur.

The event explorer must support filtering by useful dimensions such as:

- event type;
- service or producer;
- Kafka topic where applicable;
- correlation identifier;
- collection run;
- content item.

The live event view must be a bounded diagnostic stream, not an attempt to expose unlimited Kafka history directly in the browser.

### R17 — visual processing-flow inspection

Feature: `DIAGNOSTICS.PROCESSING_FLOW`.

The user must be able to select a collection run or collected item and inspect a visual representation of its processing path.

The visualization should represent relevant stages such as:

- external source;
- collector;
- Kafka publication/consumption boundaries;
- normalization;
- deduplication;
- analysis;
- PostgreSQL persistence;
- final result availability.

Where available, the visualization should expose diagnostic metadata such as:

- event timestamps;
- processing durations;
- Kafka topic/partition/offset;
- retry state;
- analysis outcome;
- persisted entity identity;
- trace identifier.

The visualization is an application-level diagnostic feature and does not replace distributed tracing in the observability platform.

### R18 — application observability

Feature: `OBSERVABILITY.APPLICATION`.

Backend services must emit structured telemetry sufficient to operate and study the system.

The initial observability model must include:

- metrics;
- structured logs;
- distributed traces;
- health/readiness information.

OpenTelemetry should be the common instrumentation and propagation standard wherever practical.

Important asynchronous boundaries, external requests, and database operations must preserve trace context where supported.

### R19 — infrastructure observability

Feature: `OBSERVABILITY.INFRASTRUCTURE`.

The local deployment must make infrastructure and runtime behavior inspectable through Grafana or equivalent observability views.

The initial dashboards should make it possible to observe at least:

- service request rate and latency;
- collection success/failure rate;
- external-source latency and failures;
- items discovered and processed;
- deduplication counts;
- Kafka consumer lag;
- analysis duration and failures;
- PostgreSQL operation latency at an appropriate aggregate level;
- JVM/runtime resource indicators;
- Kubernetes replica and restart behavior where available.

Application-owned UI and Grafana serve different purposes: the application UI explains domain/event flow, while Grafana explains system health and technical telemetry.

### R20 — failure visibility and dead-letter handling

Feature: `RELIABILITY.KAFKA_RETRY`, `RELIABILITY.DEAD_LETTER`.

Failures in asynchronous processing must be visible and must not result in silent loss of important work.

Retryable failures should use bounded retry behavior appropriate to the operation. Permanently failing asynchronous messages must have an explicit terminal handling strategy such as a dead-letter topic or equivalent failure state.

The system must make it possible to inspect failed items and understand the reason for failure.

A later implementation increment may add controlled replay after the underlying problem is corrected.

### R21 — idempotent processing

Feature: `RELIABILITY.IDEMPOTENCY`.

Kafka consumers and persistence operations must be designed for at-least-once delivery semantics and duplicate event delivery.

Reprocessing the same event must not create uncontrolled duplicate domain state or repeat non-idempotent side effects without protection.

The concrete idempotency strategy belongs to the backend technical sub-specification.

### R22 — database/event consistency

Feature: `ANALYSIS.OUTBOX`.

Where a backend operation must both persist authoritative state and publish a corresponding integration event, the design must explicitly address the failure window between database commit and Kafka publication.

The preferred production-style solution is a transactional outbox or another mechanism with equivalent consistency properties.

The first minimal vertical slice may defer full outbox implementation only if the limitation is explicit and the later migration path is preserved.

### R23 — concurrency model suitable for external I/O

Feature: `RUNTIME.CONCURRENCY`, `SECURITY.EXTERNAL_SOURCE_ACCESS`.

External collection is expected to be dominated by network I/O.

The Java backend must keep concurrency models explicit and simple.

Generic external-source retrieval must use Micronaut's managed low-level HTTP client for dynamic absolute URLs.

Its synchronous module-facing contract must run on Micronaut's blocking executor. On Java 21, that executor uses Virtual Threads. Imperative collection work must not block Netty event-loop threads.

The following limits must remain explicit:

- source concurrency;
- connect/read/request timeouts;
- response size;
- redirects;
- connection-pool capacity;
- protection against overwhelming external services.

Outbound destination policy must cover SSRF-sensitive addresses and redirect targets before source configuration is accepted from untrusted users.

The low cost of Virtual Threads must not be treated as permission for unbounded external concurrency. `Publisher`/reactive types remain appropriate for genuine streaming boundaries such as SSE.

REST controllers that invoke JDBC, blocking HTTP, or other blocking application workflows must use `@ExecuteOn(TaskExecutors.BLOCKING)` or an equivalent explicit blocking boundary.

Streaming/reactive controllers must not be moved to blocking execution mechanically. Client/server filters must remain non-blocking unless they explicitly offload blocking work.

Expected API/domain failures should be translated through Micronaut HTTP exception handlers instead of repeated controller-local error mapping.

### R24 — Kubernetes deployment

Feature: `DEPLOYMENT.KUBERNETES`.

The complete application must be runnable in a local Kubernetes environment.

The deployment must include, either directly or through development-oriented dependencies:

- application backend workloads;
- frontend workload or static frontend serving boundary;
- Kafka;
- PostgreSQL;
- required observability components.

The exact local Kubernetes distribution is not part of the product contract, but `kind` or `k3d` are preferred initial development targets.

### R25 — independent backend and frontend delivery boundaries

Feature: `DELIVERY.FRONTEND_BACKEND_BOUNDARY`, `CONTRACTS.HTTP`.

Backend and frontend are independent application deliverables and should be maintained in separate repositories.

The backend repository may contain multiple backend services in one multi-module build. The project should not create one repository per service unless an actual lifecycle need emerges.

Frontend/backend integration must use explicit application contracts rather than source-level coupling.

OpenAPI should be used for conventional HTTP APIs where practical. Event-stream payload contracts must also be explicit and versionable.

### R26 — horizontal processing scalability

Feature: `SCALABILITY.KAFKA_CONSUMERS`.

Kafka-backed worker stages must support multiple consumer instances where partitioning permits parallelism.

The design must make it possible to demonstrate that increasing worker replicas can reduce processing backlog without changing functional semantics.

A later increment may add Kafka-lag-driven autoscaling through KEDA or equivalent Kubernetes mechanisms.

### R27 — explicit data provenance

Feature: `DATA.PROVENANCE`.

Every collected result must retain enough provenance to explain where it came from.

At minimum, provenance should include:

- source identity;
- external URL or identifier when available;
- collection time;
- collection run identity.

Analysis must not erase or replace original-source provenance.

### R28 — bounded event-observation persistence

Feature: `DIAGNOSTICS.EVENT_OBSERVATION`.

If application events are persisted to support the event explorer, that storage is diagnostic materialization rather than the authoritative Kafka log.

Retention must be bounded by count, age, or another explicit policy so the diagnostic feature does not grow without limit.

The event explorer must tolerate older diagnostic records being removed.

### R29 — deterministic local testability

Feature: `TESTING.DETERMINISTIC_LOCAL`.

Core configuration, scheduling decisions, normalization, deduplication, analysis, event-contract handling, and persistence behavior must be testable deterministically without relying on live Internet services.

External-source adapters must support fixtures, stubs, local test servers, or equivalent deterministic test mechanisms.

Kafka and PostgreSQL integration behavior should be covered using disposable test infrastructure such as Testcontainers where practical.

### R30 — secrets and external-service safety

Feature: `SECURITY.EXTERNAL_SOURCE_ACCESS`.

Credentials, tokens, and other secrets required by external sources or optional analyzers must not be stored in source control.

The configuration model must distinguish ordinary user-editable source configuration from sensitive secret material.

Collection must use explicit timeouts and bounded concurrency. The system must not intentionally bypass external access controls, authentication restrictions, robots policies, rate limits, or terms that prohibit automated access.

### R31 — authenticated identities and additive role-based authorization

Feature: `SECURITY.IDENTITY_ROLES`, `SECURITY.AUTHENTICATION`, `SECURITY.AUTHORIZATION`.

The backend must authenticate access to protected application APIs and authorize operations from explicit roles carried by the authenticated principal.

User accounts are persisted application identities.

A human user must receive `USER` by default when created. Additional roles are additive and explicit. They must not be inferred from a hidden hierarchy.

The initial role vocabulary must include at least:

- `USER` — human application identity and baseline authenticated access;
- `VIEWER` — read-only access to the consumer-facing result experience and the backend result APIs required by it;
- `ADMIN` — access to configuration, operational administration, and technical diagnostic capabilities;
- `BOT` — non-human/system identity for machine-oriented access where required.

A system identity may have `BOT` without `USER`.

An administrator who also needs the consumer-facing result experience may have both `ADMIN` and `VIEWER`. Authorization must not rely on an implicit `ADMIN > VIEWER > USER` hierarchy.

Authentication must use signed, time-bounded JWT credentials and remain stateless on the backend. PostgreSQL must not contain server-side login-session records.

User identity, enabled/disabled state, and assigned roles may be persisted. The current authenticated request context is determined by validation of the presented JWT.

JWT validation must cover:

- signature;
- expiry;
- issuer/audience expectations;
- principal and role claims required by authorization.

Browser transport must support REST and native SSE without exposing JWTs in URLs. Cross-origin, cookie, and CSRF policy must be explicit before public/shared deployment.

Backend authorization is the security boundary. Hiding routes or navigation in the frontend is not sufficient protection.

### R32 — role-specific browser experience

Feature: `PRESENTATION.VIEWER_RESULTS`, `DIAGNOSTICS.ANALYSIS_INSPECTION`, `SECURITY.AUTHORIZATION`.

The product must distinguish the expert/administrative application experience from the normal result-consumption experience.

Users with `ADMIN` may access backend-authorized administrative and diagnostic workflows, including:

- configuration;
- Collection operations;
- `DIAGNOSTICS.ANALYSIS_INSPECTION`;
- Event Explorer;
- Processing Flow.

Users with `VIEWER` must have a consumer-facing interface for browsing and inspecting relevant Results. That experience must not expose internal operational or infrastructure details that are outside viewer scope.

The consumer-facing interface should reuse existing Results contracts when they already provide the required data. A duplicate Results API must not be introduced only to support different presentation.

Backend authorization must ensure that a `VIEWER` cannot call protected admin or diagnostic endpoints directly.

Detailed React routing, layout, presentation, and component behavior belong to the `signalharvester-web` specification tree.


## Scenarios

### S1 — configure and collect scientific data

A user creates a monitoring profile named `Recent Climate Research`, enables several configured scientific-data sources, sets a collection interval, and enables the profile.

At the next due time the system creates a collection run and queries the enabled sources.

It emits discovered items into Kafka-backed processing, removes duplicates, analyzes the remaining items, stores accepted results, and pushes newly available results to the open browser feed.

The user sees the newly collected information without refreshing the page.

### S2 — add a compatible source without deployment

A user adds a new REST, RSS, or configurable HTML source using an already supported collector type and attaches it to an enabled profile.

The user tests the source successfully through the UI.

The next scheduled run includes that source automatically. No application restart, new Java class, image rebuild, or Kubernetes deployment is required solely because the source configuration was added.

### S3 — monitor an AI development topic

A user creates an `AI Development` information profile using configured article/news sources.

The system periodically collects newly published entries and normalizes them into the common content model.

It deduplicates previously seen entries, applies configured topic analysis, stores the results, and displays relevant new entries in the live feed.

### S4 — inspect one item's event path

The user opens a newly collected item and selects its technical processing view.

The UI reconstructs the item's correlated path from source collection through Kafka-backed processing, analysis, and persistence.

The user can inspect timestamps, relevant event metadata, analysis outcome, and trace identity without opening Kafka command-line tooling.

### S5 — duplicate rediscovery

A source returns the same external item on several scheduled collection runs.

The first discovery creates the normal domain result. Later discoveries are recognized according to the deduplication contract and do not create uncontrolled duplicate records.

The event explorer makes the duplicate decision visible.

### S6 — temporary external-source failure

One configured source times out while other sources in the same collection run succeed.

The failed source is reported as failed or retried according to policy without discarding successful results from unrelated sources.

The collection run, application UI, logs, metrics, and traces expose enough information to diagnose the partial failure.

### S7 — asynchronous processing failure

An item reaches an analysis consumer but repeatedly fails because of malformed content or another terminal condition.

The failure is retried only according to bounded policy and then becomes inspectable through the dead-letter/failure handling path rather than disappearing silently or blocking unrelated Kafka records indefinitely.

### S8 — browser reconnects to the live feed

A user leaves the result page open while the SSE connection is interrupted.

The frontend detects disconnection and reconnects according to the selected live-stream contract.

The application remains usable. The user can recover authoritative current results through the normal query API even if some transient live notifications were missed.

### S9 — scale a Kafka consumer workload

A demonstration produces enough input to create visible Kafka consumer lag.

The operator increases the number of compatible worker replicas, manually or through a later autoscaling increment. The backlog decreases while duplicate protection and processing semantics remain correct.

The effect is visible in technical observability dashboards.

### S10 — trace a slow collection

An external source becomes unusually slow.

The corresponding collection run remains visible in the application, while distributed traces and metrics show that external-request latency, rather than Kafka or PostgreSQL processing, is the dominant contributor.

### S11 — restart the distributed deployment

Application workloads restart while PostgreSQL and Kafka retain their configured development state.

Persisted configuration and domain results remain available. Kafka consumers resume according to their consumer-group state, and normal duplicate/idempotency protections remain effective.

### S12 — compare application flow with infrastructure telemetry

The user selects a slow or failed item in the Event Explorer and obtains its trace identifier.

The application-level view explains the logical processing stages, while the observability platform allows the same operation to be inspected in detailed distributed traces, logs, and service metrics.

### S13 — authenticate and separate viewer/admin access

A persisted human account authenticates and receives a signed JWT representing its stable identity and explicit assigned roles. No server-side login session is inserted into PostgreSQL.

A principal with `USER` and `VIEWER` can open the consumer-facing result experience and use its required Results read/live contracts.

Backend authorization rejects direct requests from that principal to administrative configuration or technical diagnostic endpoints.

A principal with `USER`, `VIEWER`, and `ADMIN` can use both the consumer-facing result experience and authorized administrative workflows.

A machine identity may instead authenticate with `BOT` without being treated as an interactive human user.

## Non-goals

The initial product does not require:

- a public multi-tenant SaaS platform;
- public self-service user registration, organizations, billing, or subscription plans;
- mobile applications;
- native desktop applications;
- direct browser access to Kafka or PostgreSQL;
- full Kafka administration functionality comparable to dedicated Kafka management products;
- replacement of Grafana with custom observability dashboards;
- a general-purpose web crawler capable of interpreting arbitrary websites without configuration;
- bypassing authentication, anti-bot protection, rate limits, or site restrictions;
- perfect extraction from JavaScript-heavy sites that require browser automation;
- Elasticsearch, OpenSearch, or another separate search cluster in the first version;
- mandatory AI/LLM dependencies for core operation;
- Kubernetes deployment to a paid public cloud;
- exactly-once semantics across all system boundaries;
- one repository per backend service;
- a service mesh;
- event sourcing as the authoritative domain persistence model;
- a custom replacement for Kafka's durable log.

## Design constraints

- Keep the umbrella specification focused on product and system-level behavior; move framework- and class-level choices into sub-specifications.
- Prefer explicit event and API contracts over implicit coupling between services or repositories.
- Keep Kafka topic count intentional. Conceptual processing stages do not automatically require one physical topic each.
- Keep service count intentional. The system should demonstrate distributed boundaries without decomposing every class or use case into a separate microservice.
- Keep PostgreSQL authoritative for application state that requires transactional persistence.
- Treat Kafka as asynchronous transport and durable event infrastructure, not as a replacement for all application persistence.
- Keep the browser isolated from infrastructure protocols.
- Prefer SSE over WebSocket for initial unidirectional live streams; introduce WebSocket only for a demonstrated bidirectional requirement.
- Keep blocking application workflows imperative and run them on Micronaut's blocking executor, which is Virtual-Thread backed on the Java 21 baseline; never block Netty event-loop threads.
- Preserve backpressure through bounded source concurrency, HTTP connection/resource limits, Kafka consumer flow control, and explicit downstream capacity limits.
  Use `Publisher` for genuinely streaming boundaries such as SSE instead of forcing all workflows into one concurrency model.
- Keep collection adapters isolated from normalized domain processing.
- Prefer configuration-driven integration when sources share a common protocol/extraction model.
- Keep analysis replaceable and allow deterministic non-AI operation.
- Preserve source provenance throughout the pipeline.
- Make failure states explicit and observable.
- Design consumers for duplicate delivery from the beginning.
- Keep custom event visualization and standard OpenTelemetry tracing complementary rather than duplicating the same concern poorly.
- Keep local development reproducible and suitable for automated tests.

## Repository boundaries

The intended product-level repository boundary remains separate backend and frontend repositories.

The backend repository is `signalharvester`. It starts as a Gradle modular monolith with one deployable application.

Functional backend capabilities are separate Gradle modules, not separate deployments. A future deployment repository may be introduced when Kubernetes/GitOps configuration becomes independently substantial.

The backend modules must remain extractable by contract rather than by premature deployment. Public module APIs, Kafka event contracts, and logical PostgreSQL ownership are the primary extraction boundaries.

## Compatibility / migration

This is the initial product specification, so there is no existing production data contract to migrate.

Nevertheless, the first implementation should establish migration-safe boundaries early:

- PostgreSQL schema changes must use an explicit migration mechanism such as Flyway;
- externally visible HTTP contracts must be versionable;
- Kafka event contracts must use versioned Protocol Buffers schemas and follow explicit backward/forward compatibility rules before incompatible changes are introduced;
- removed Protocol Buffers field numbers and names must be reserved and must not be silently reused for unrelated meanings;
- persisted source configuration must tolerate additive collector capabilities where practical;
- diagnostic event storage must not become an accidental long-term compatibility contract;
- analyzer implementations may evolve without changing the identity or provenance of the underlying collected item.

Technical sub-specifications must define compatibility rules before introducing a contract that would make later migration expensive.

## Validation

Initial umbrella acceptance requires a working end-to-end deployment in which:

1. the complete application runs in a local Kubernetes environment;
2. PostgreSQL stores application configuration and collected results;
3. Kafka carries real asynchronous application events between backend processing stages;
4. at least one real structured or semi-structured external data source is configured and collected successfully;
5. at least one real topic/news information source is configured and collected successfully;
6. a source compatible with an existing generic collector can be added through configuration without a backend redeployment;
7. the browser can create and edit monitoring profiles and sources;
8. scheduled collection operates without manual triggering;
9. newly available results appear in an already-open browser page without manual refresh;
10. repeated discovery of the same external item demonstrates deterministic duplicate handling;
11. a selected item or collection run can be inspected in the technical event explorer;
12. a selected processing flow can be visualized across major application stages;
13. metrics, structured logs, and distributed traces are available through the observability stack;
14. a trace can be correlated with an application-level collection or item-processing flow;
15. at least one asynchronous failure scenario demonstrates bounded retry and explicit terminal failure handling;
16. backend tests exercise deterministic collection fixtures plus PostgreSQL/Kafka integration where appropriate;
17. frontend tests cover critical configuration and live-update behavior at an appropriate level;
18. authenticated requests demonstrate backend-enforced separation between `VIEWER` result access and `ADMIN` operational/diagnostic access without server-side login sessions;
19. repository documentation explains how to start the local environment and observe one complete collection flow.

Performance is not defined by a production-scale numerical SLA in the initial product.

Instead, the implementation must support a repeatable demonstration. Concurrent collection and Kafka backlog behavior must be observable and understandable in that demonstration.

## Implementation coordination

This umbrella is implemented through bounded sub-specifications rather than a permanently maintained implementation checklist.

- When `current_focus` is present, it identifies the bounded backend implementation focus and that focus is summarized in [`../README.md`](../README.md).
- Accepted backend implementation truth belongs in current-state documentation such as `docs/IMPLEMENTATION.md`, module READMEs, and module contracts.
- Historical accepted sub-specifications are indexed from `docs/specs/README.md` and archived after developer acceptance.
- Frontend implementation sequencing and current status belong to the separate `signalharvester-web` specification tree.
- Cross-repository umbrella acceptance must use evidence from both independently owned deliverables rather than duplicating one repository's current-state inventory in the other.
