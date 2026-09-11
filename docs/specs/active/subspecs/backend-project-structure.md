---
type: Specification
title: SignalHarvester backend project structure and module boundaries
description: Active technical sub-specification defining the initial Gradle modular-monolith structure, module ownership rules, contracts, and integration-testing strategy.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: active
---
# SignalHarvester backend project structure and module boundaries

## Status

Active supporting technical sub-specification — the initial repository structure is established and these requirements remain boundary guardrails while later vertical slices exercise them.

The backend starts as a modular monolith. There is one deployable backend application, while the codebase is split into independently owned functional Gradle modules.

The repository structure is intentionally simpler than strict Hexagonal Architecture. It preserves the most important property of ports-and-adapters design — explicit boundaries around replaceable dependencies and module contracts — without requiring every use case to be wrapped in a fixed set of layers or interfaces.

## Goal

Create a backend structure that:

- uses Gradle Kotlin DSL;
- produces one deployable backend application initially;
- organizes code by cohesive functional capability rather than repository-wide technical layers;
- keeps each functional module as self-contained as practical;
- makes inter-module contracts explicit;
- allows lower-level shared code where sharing is intentional and stable;
- prevents direct access to another module's internal implementation;
- permits selected modules to be extracted into independently deployed services later;
- supports Kafka, PostgreSQL, REST/SSE, external HTTP sources, and OpenTelemetry;
- supports realistic module, component, and end-to-end integration tests;
- avoids unnecessary architectural ceremony while keeping boundaries enforceable.

## Current state

The backend repository root is `signalharvester/` and the Gradle root project name is `signalharvester`.

The repository now contains the runnable Micronaut composition root, PostgreSQL-backed configuration persistence/REST, collection HTTP and Kafka adapters, collection-run orchestration, and the first analysis Kafka/persistence path. Scheduling/monitoring profiles, source-specific parsing, results/event-observation implementations, deployment infrastructure, and broader architecture enforcement remain pending.

Java 21 is the initial toolchain target. Micronaut is the backend framework. Generic external-source access uses Micronaut's managed low-level HTTP client for configuration-driven absolute URLs, behind synchronous module-facing APIs executed on Micronaut's blocking executor, which uses Virtual Threads on the Java 21 baseline. Streaming boundaries remain reactive where appropriate.

## Repository structure

```text
signalharvester/
├── app/
│   └── src/
│
├── common/
│   └── src/
│
├── contracts/
│   ├── api-contracts/
│   │   └── src/main/resources/openapi/
│   └── event-contracts/
│       └── src/main/proto/
│           └── io/signalharvester/events/
│               ├── common/v1/
│               ├── collection/v1/
│               ├── analysis/v1/
│               └── results/v1/
│
├── modules/
│   ├── configuration/
│   │   └── src/
│   ├── collection/
│   │   └── src/
│   ├── analysis/
│   │   └── src/
│   ├── results/
│   │   └── src/
│   └── event-observation/
│       └── src/
│
├── testing/
│   ├── test-support/
│   └── integration-tests/
│
├── infra/
│   ├── docker-compose/
│   ├── kubernetes/
│   └── observability/
│
├── docs/specs/
├── settings.gradle.kts
├── build.gradle.kts
└── gradle.properties
```

Directory names use lowercase and kebab-case only.

## Architectural approach

The backend is a modular monolith, not a collection of microservices.

Initially the complete backend is packaged and deployed as one application:

```text
frontend
    ↓ REST / SSE
signalharvester-backend
    ├── configuration
    ├── collection
    ├── analysis
    ├── results
    └── event-observation
        ↓
Kafka + PostgreSQL + observability infrastructure
```

The modules are compile-time code boundaries, not deployment boundaries.

A module represents a cohesive capability. It must not represent a repository-wide technical layer such as `controllers`, `services`, `repositories`, or `entities`.

Each functional module should own all implementation details required for its capability, including whichever of the following are relevant:

- domain/model classes;
- application/use-case logic;
- public module interfaces;
- persistence implementation and migrations;
- Kafka producers and consumers;
- external HTTP clients and parsers;
- REST controllers owned by the capability;
- validation;
- module-specific configuration;
- module tests.

A module may internally use packages such as `api`, `application`, `model`, and `infrastructure`, but this is an internal organization choice rather than a repository-wide layering scheme.

## Module contract rule

Every functional module has an internal implementation surface and may expose a narrow public contract surface.

The preferred Java namespace convention is:

```text
io.signalharvester.<module>.api
io.signalharvester.<module>.<internal packages>
```

Other functional modules may import types from `<module>.api` when synchronous collaboration is required.

They must not import implementation classes from another module's internal packages.

Example:

```text
collection
    ↓ allowed
configuration.api.SourceConfigurationProvider

collection
    ↓ forbidden
configuration.persistence.JdbcSourceConfigurationRepository
```

This rule should later be enforced with architecture tests, preferably ArchUnit.

Public module APIs should remain small. A class must not be moved to an `api` package merely because another module wants convenient access to it.

## Interfaces and contracts

Interfaces are introduced when they represent a real boundary.

Good candidates include:

- a public capability exposed by one functional module to another;
- an external service abstraction;
- a replaceable analysis engine;
- source-fetching or source-parsing strategies;
- clock/ID generation when deterministic testing requires substitution;
- publication of domain/integration events;
- infrastructure that has more than one meaningful implementation or must be isolated in tests.

Interfaces must not be created mechanically for every concrete class.

The following pattern is discouraged unless a real substitution boundary exists:

```text
FooService
FooServiceImpl
```

A concrete application class is preferred when there is no meaningful alternative implementation or external boundary.

## Requirements

### R1 — Gradle multi-project modular monolith

The backend must use Gradle Kotlin DSL.

Each functional module must be a Gradle subproject.

The initial Gradle projects are:

1. `app`;
2. `common`;
3. `contracts:api-contracts`;
4. `contracts:event-contracts`;
5. `modules:configuration`;
6. `modules:collection`;
7. `modules:analysis`;
8. `modules:results`;
9. `modules:event-observation`;
10. `testing:test-support`;
11. `testing:integration-tests`.

Only `app` is an executable backend application initially.

Functional modules use `java-library` semantics and are not independently deployable applications at this stage.

### R2 — app is the composition root

`app` owns application startup and cross-cutting runtime composition.

Its responsibilities include:

- Micronaut application bootstrap;
- dependency wiring that is genuinely application-wide;
- application-wide configuration loading;
- enabling module discovery;
- packaging the executable artifact;
- startup/shutdown integration;
- top-level health/readiness wiring where required.

`app` must not become a location for business logic.

Business behavior belongs to the functional module that owns the capability.

### R3 — configuration module ownership

`modules:configuration` owns the configuration capability end to end.

Its expected responsibilities include:

- monitoring-profile definition and persistence;
- external-source definition and persistence;
- schedules and enable/disable state;
- source-to-profile association;
- configurable source extraction settings;
- validation of configuration commands;
- REST endpoints for configuration management;
- a public module API for reading effective configuration when another module requires synchronous access;
- publication of configuration-change events where asynchronous reaction is more appropriate.

Its persistence implementation belongs inside this module.

Other modules must not query configuration tables directly.

### R4 — collection module ownership

`modules:collection` owns collection orchestration and source access end to end.

Its expected responsibilities include:

- determining which collection work is due;
- collection-run lifecycle;
- reading effective source configuration through the configuration module contract;
- source fetching;
- source parsing/extraction;
- HTTP/RSS/HTML/API adapters;
- Micronaut-managed low-level HTTP client behind a synchronous collection-owned transport contract for dynamic absolute source URLs;
- bounded source fetching on `TaskExecutors.BLOCKING`, which uses Virtual Threads on the Java 21 baseline;
- explicit connect/read/request timeouts, response-size limits, redirect limits, connection-pool limits, and collection-owned HTTP error mapping;
- an explicit outbound destination/SSRF policy before configured source URLs are accepted from untrusted users; automatic redirects must be considered part of that policy;
- source-specific retry/timeout behavior;
- raw-item discovery;
- collection status events;
- publication of raw content events to Kafka.

Source-specific implementation details remain private to this module.

### R5 — analysis module ownership

`modules:analysis` owns content processing after raw discovery.

Its expected responsibilities include:

- consuming raw-item events;
- normalization;
- deterministic fingerprint calculation where applicable;
- duplicate detection policy owned by the analysis process;
- deterministic rule-based analysis;
- optional AI-backed analysis through a replaceable interface;
- scoring, tagging, and classification;
- publication of analyzed-item events;
- persistence needed by analysis itself.

Provider-specific AI SDK types must not leak into the module's application model or public contract.

The module must remain functional without an LLM.

### R6 — results module ownership

`modules:results` owns the user-facing read model for collected information.

Its expected responsibilities include:

- consuming analyzed-item events;
- maintaining the result projection used by the UI;
- persistence of result/read-model data;
- query REST endpoints;
- filtering, sorting, and pagination;
- item-detail queries;
- SSE publication of live business updates to browser clients.

The browser must not connect directly to Kafka.

### R7 — event-observation module ownership

`modules:event-observation` owns the application-level technical Event Explorer capability.

Its expected responsibilities include:

- observing selected application and Kafka events;
- maintaining bounded technical event history;
- correlating events by event ID, correlation ID, collection-run ID, item ID, and trace ID where available;
- exposing technical event queries;
- exposing the live technical SSE stream;
- reconstructing event-flow data required by the frontend visualization.

It must not become a substitute for OpenTelemetry, Prometheus, Loki, Tempo, or Grafana.

### R7a — HTTP server execution model

Micronaut Netty event-loop threads must not execute blocking application work.

REST controller methods or classes that invoke JDBC, blocking source access, or other imperative blocking workflows must use `@ExecuteOn(TaskExecutors.BLOCKING)` or an equivalent explicit blocking execution boundary. On the Java 21 baseline the blocking executor is Virtual-Thread backed.

Controllers that expose true streaming/reactive responses, especially SSE, must keep their reactive execution model instead of being moved to blocking execution mechanically.

Micronaut client/server filters should remain lightweight and non-blocking. A filter that performs blocking work must explicitly offload that work and must not block a Netty event loop. Expected application/domain failures should be mapped through centralized Micronaut `ExceptionHandler` implementations at the HTTP boundary rather than duplicated controller `try/catch` logic.

### R8 — common contains only genuinely cross-cutting primitives

`common` is an allowed lower-level shared module.

It may contain stable primitives that are genuinely useful to multiple modules, for example:

- strongly typed identifiers;
- time abstractions;
- small validation primitives;
- generic pagination primitives;
- shared error/value types with application-wide semantics;
- small utilities with no ownership in a single functional module.

It must not become a dumping ground.

The following must remain outside `common` unless there is clear cross-module ownership:

- business entities from one functional module;
- repositories;
- module-specific DTOs;
- module-specific services;
- arbitrary static helper classes added only to avoid a small amount of duplication.

Some duplication is preferable to premature coupling through `common`.

### R9 — event contracts are Protocol Buffers integration contracts

`contracts:event-contracts` is reserved for payloads that intentionally cross Kafka-backed asynchronous boundaries.

Protocol Buffers is the canonical wire-contract format for these Kafka integration events. The authoritative sources are versioned `.proto` files under:

```text
contracts/event-contracts/src/main/proto/io/signalharvester/events/
```

The initial schema groups are:

```text
common/v1
collection/v1
analysis/v1
results/v1
```

The contract module may define:

- a common event envelope;
- integration-event messages;
- schema/package versioning;
- event and correlation identifiers carried on the wire;
- reusable protocol-level value messages that genuinely belong to the integration contract.

Generated Java classes are build output. They must be generated by Gradle from `.proto` definitions and must not be manually maintained as parallel source-of-truth Java DTOs.

The contract module must not contain event handlers, Kafka clients, repositories, application services, or module business logic.

Protobuf-generated message classes are transport types, not domain entities. A functional module may map between a generated event message and its own internal model rather than allowing generated classes to spread through unrelated application logic.

Kafka remains useful inside the modular monolith because it models the intended asynchronous boundaries and makes event flow, lag, retries, replay, schema evolution, and observability visible.

The fact that producer and consumer initially run in the same process does not permit direct invocation to replace a deliberately asynchronous contract without an explicit architectural decision.

Detailed schema-evolution and compatibility rules are defined in `backend-event-contracts.md`.

### R10 — REST API is an external contract

Browser-facing and test-facing HTTP APIs must be explicit contracts.

REST controllers belong to the functional module that owns the capability they expose.

Examples:

```text
configuration module
    └── configuration REST controllers

results module
    └── result query and feed controllers

event-observation module
    └── event explorer controllers
```

`app` does not own a generic controller layer.

OpenAPI definitions and API-generation/validation configuration belong to `contracts:api-contracts`.

REST request/response payloads remain JSON-oriented contracts. Server-Sent Events exposed to the browser also use browser-friendly JSON payloads. Protobuf is not used merely to make in-process Java calls or browser APIs more complex.

The frontend and external black-box test clients must depend on the HTTP contract, not Java implementation classes.

### R11 — no cross-module database access

A functional module owns its persistence data.

Initially modules may share one PostgreSQL server and one physical database for operational simplicity.

Logical ownership must nevertheless be explicit.

The preferred initial arrangement is separate PostgreSQL schemas per persistence-owning functional module, for example:

```text
configuration.*
collection.*
analysis.*
results.*
event_observation.*
```

A module must not read or mutate another module's tables directly.

Cross-module data access must happen through:

- a public synchronous module API; or
- an asynchronous event/projection.

This rule keeps later service extraction feasible without requiring a full persistence redesign.

### R12 — module-local migrations

Database migrations belong to the module that owns the schema.

A module should maintain its own Flyway migration location rather than one global migration folder that mixes all module tables.

The application may execute all module migrations during startup, but ownership remains local to each module.

### R13 — dependency direction must remain acyclic

The Gradle module graph must remain acyclic.

`common` and contract modules are lower-level dependencies.

Functional modules should avoid synchronous dependencies on one another unless there is a clear reason.

The expected initial graph is approximately:

```text
                    common
                      ▲
                      │
              event-contracts
              ▲   ▲   ▲   ▲
              │   │   │   │
configuration │ collection │ analysis │ results │ event-observation
       ▲              │
       └──────── collection

app → all functional modules
```

The collection-to-configuration dependency is intentional because collection needs current effective source configuration. It must use only the configuration module's public API.

Most processing relationships between collection, analysis, results, and event observation should be asynchronous through Kafka rather than direct module calls.

### R14 — module internals should be architecture-testable

The project should add ArchUnit once real Java packages exist.

Architecture tests should be able to verify at least:

- no module imports another module's internal packages;
- `app` is not imported by functional modules;
- contract modules do not depend on functional implementation modules;
- persistence adapters from one module are not used by another module;
- controller classes do not become a generic cross-module API layer.

Architecture tests should enforce meaningful boundaries rather than arbitrary package naming rules.

### R15 — integration testing remains primarily Java-based

The primary integration test stack should be:

- JUnit 5;
- Testcontainers;
- PostgreSQL container;
- Kafka container;
- Micronaut test/runtime support;
- a local synthetic HTTP server for external-source scenarios.

`testing:integration-tests` owns black-box and multi-module backend scenarios.

`testing:test-support` may contain reusable test fixtures, container configuration, deterministic clocks/IDs, and synthetic source helpers.

### R16 — integration tests must exercise contracts and infrastructure

Integration tests should avoid mocking Kafka or PostgreSQL when the purpose of the test is to validate asynchronous or persistence behavior.

Important target scenarios include:

1. create configuration through REST;
2. trigger or wait for a collection run;
3. collect from a deterministic local HTTP source;
4. publish and consume Kafka events;
5. analyze the discovered item;
6. materialize the result in PostgreSQL;
7. query the result through REST;
8. observe the corresponding SSE update;
9. inspect the technical event path through the Event Explorer API.

These scenarios may run against one backend process while still exercising the real module boundaries and external infrastructure.

### R17 — Python is optional black-box tooling, not the primary integration-test stack

Python is not required for the initial backend test suite.

It may later be introduced for independently implemented black-box tooling such as:

- load generation;
- scenario scripting;
- long-running collection simulations;
- synthetic external-source servers;
- event/API investigation scripts;
- chaos/demo orchestration.

Python tooling must interact through public protocols such as HTTP/SSE and must not become a second source of backend business logic.

### R18 — extraction into microservices is driven by runtime need

A functional module may later become a separately deployed service when there is a concrete reason, for example:

- independent scaling requirements;
- resource isolation;
- significantly different availability requirements;
- independent release cadence;
- heavy or expensive analysis workloads;
- operational isolation of unstable external-source collection;
- ownership by a different team.

Extraction must not be performed merely to demonstrate microservices.

The initial modular boundaries, event contracts, schema ownership, and public APIs should make such extraction possible without making it a current requirement.

## Initial package guidance

When implementation starts, a functional module may use a structure similar to:

```text
io.signalharvester.collection
├── api/
├── run/
├── source/
│   ├── http/
│   ├── rss/
│   └── html/
├── messaging/
├── persistence/
└── configuration/
```

The exact packages should follow the module's concepts rather than a universal layered template.

For example, this is discouraged:

```text
io.signalharvester.collection
├── controller/
├── service/
├── repository/
├── entity/
└── dto/
```

when those folders merely separate technical roles and force one feature to be changed across many unrelated packages.

Prefer feature/cohesion-oriented internal grouping once a module becomes large enough to need it.

## Scenarios

### S1 — add a new configurable source

1. The UI sends a configuration command to the configuration module's REST endpoint.
2. The configuration module validates and persists the source in its own schema.
3. Collection obtains the effective configuration through the configuration public API or reacts to an explicit configuration event.
4. No collection implementation or configuration table is accessed directly from another module.

### S2 — process a discovered item asynchronously

1. Collection discovers an item.
2. Collection publishes the versioned raw-item event.
3. Analysis consumes it from Kafka.
4. Analysis produces an analyzed-item event.
5. Results consumes the analyzed event and updates its read model.
6. Event observation sees the relevant event metadata and makes the path visible to the UI.

No direct `collection -> analysis -> results` method chain is required.

### S3 — test the full backend without frontend code

1. An integration test starts PostgreSQL, Kafka, and a deterministic HTTP source through Testcontainers/test support.
2. It starts the complete `app` composition.
3. It configures a monitoring source through REST.
4. It triggers collection through a public endpoint or deterministic scheduler hook.
5. It verifies the final result through REST and optionally SSE.
6. It verifies event-flow metadata through the technical API.

### S4 — extract analysis later

1. The analysis module already consumes and publishes versioned Kafka contracts.
2. Its persistence is logically owned by the analysis module.
3. It does not depend on `app` or another module's internals.
4. A future analysis-service application can compose the same module without changing its core processing code.

## Non-goals

The initial structure does not attempt to provide:

- one deployable service per module;
- a strict Clean Architecture or Hexagonal Architecture template;
- an interface for every class;
- separate PostgreSQL server instances per module;
- JPMS module descriptors from the first commit;
- a universal `service/repository/controller/entity` package layout;
- a generic shared framework abstraction over Micronaut;
- a Python-based primary test suite;
- distributed transactions between modules;
- premature API gateways or service discovery.

## Design constraints

- Functional cohesion is more important than technical-layer uniformity.
- A module owns its behavior and infrastructure details.
- Public contracts must be deliberately smaller than module internals.
- Cross-module persistence access is forbidden.
- Cross-module implementation imports are forbidden.
- Kafka contracts remain explicit even while producer and consumer are in one process.
- Kafka integration-event wire contracts use Protocol Buffers; `.proto` files are the source of truth.
- In-process synchronous collaboration uses Java module APIs rather than Protobuf serialization.
- REST and SSE remain JSON-based external browser/test contracts unless a later requirement explicitly changes that boundary.
- Shared `common` code is allowed but must remain genuinely cross-cutting.
- The root application remains thin.
- The build must not hide all module dependencies through a dependency-heavy global Gradle block.
- Directories use lowercase/kebab-case naming.

## Compatibility / migration

This specification replaces the earlier service-first backend skeleton.

The previous separately deployable concepts:

- `control-service`;
- `collector-service`;
- `analysis-service`;
- `result-service`;
- `event-observer-service`;

are replaced by functional modules:

- `configuration`;
- `collection`;
- `analysis`;
- `results`;
- `event-observation`.

The responsibilities remain broadly similar, but deployment changes from five application processes to one backend application.

The architecture deliberately preserves future extraction paths through explicit module APIs, Kafka contracts, and persistence ownership.

## Validation

The structure is valid when all of the following are true:

- Gradle recognizes every declared subproject;
- only `app` is intended to produce the initial backend executable;
- functional modules can be tested independently;
- module dependencies are explicit and acyclic;
- source directories contain no repository-wide technical layers masquerading as modules;
- inter-module synchronous calls can be described through a public module API;
- asynchronous interactions can be described through event contracts;
- no module requires direct table access to another module's persistence;
- integration tests can boot the assembled application with real Kafka and PostgreSQL containers;
- the layout does not require a microservice deployment to validate Kafka/event-driven behavior.

## Implementation tasks

1. Bootstrap the Micronaut application in `app`.
2. Add central Gradle version management or a version catalog.
3. Add JUnit 5 and test conventions.
4. Add Testcontainers for PostgreSQL and Kafka.
5. Define the first configuration module public API.
6. Define the first REST/OpenAPI contract for source configuration.
7. Define the first versioned Protobuf Kafka event envelope and raw-item contract.
8. Implement one deterministic external-source adapter in collection.
9. Implement one non-AI analysis path.
10. Implement the first results projection and query endpoint.
11. Implement SSE for live result updates.
12. Add event-observation persistence and technical SSE.
13. Add ArchUnit rules once packages and public APIs exist.
14. Add module-local Flyway migrations.
15. Add OpenTelemetry instrumentation.
16. Add Docker Compose for local dependencies.
17. Add Kubernetes deployment after the local vertical slice is stable.
