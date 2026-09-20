---
type: Specification
title: SignalHarvester backend project structure and module boundaries
description: Active technical sub-specification defining Gradle modular-monolith boundary rules, contract ownership, dependency direction, and integration-testing strategy.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: active
---
# SignalHarvester backend project structure and module boundaries

## Status

Active supporting architecture specification.

The initial modular-monolith structure is accepted.

This document remains active only for unresolved boundary and extraction guardrails that later stages must preserve. Accepted current architecture belongs in `docs/ARCHITECTURE.md`, root/module `AGENTS.md`, and module `contract.md` files.

Do not maintain a second implementation inventory here.

## Feature scope

- `PLATFORM.MODULAR_MONOLITH` — functional-module ownership and dependency direction.
- `CONTRACTS.KAFKA_PROTOBUF` — asynchronous integration-contract boundary.
- `CONTRACTS.HTTP` — browser/test-client HTTP contract boundary.
- `RUNTIME.CONCURRENCY` — explicit blocking versus streaming execution model.
- `TESTING.DETERMINISTIC_LOCAL` — Java/Testcontainers integration strategy.
- `DELIVERY.FRONTEND_BACKEND_BOUNDARY` — independent frontend/backend delivery.

Feature identifiers are defined in [`../../../FEATURES.md`](../../../FEATURES.md).

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

The repository already implements the foundation described by this specification:

- modular Gradle layout;
- Micronaut composition root;
- deliberately published module APIs where a synchronous consumer exists;
- module-owned persistence;
- contract modules;
- ArchUnit boundary checks;
- dedicated integration-test source sets.

Later capabilities must preserve those boundaries. Detailed implementation status belongs in `docs/IMPLEMENTATION.md`; this supporting spec only defines architectural constraints that still matter for future work.

## Repository navigation

This supporting specification does not maintain a second current repository tree.

Use the following authoritative current-state sources instead:

- root [`README.md`](../../../../README.md) — top-level repository layout and primary navigation;
- [`modules/README.md`](../../../../modules/README.md) — current functional-module inventory and module documentation entry points;
- [`contracts/README.md`](../../../../contracts/README.md) — current REST and event-contract source ownership;
- [`docs/ARCHITECTURE.md`](../../../ARCHITECTURE.md) — accepted architecture and dependency direction;
- `settings.gradle.kts` — actual Gradle subproject registration.

This specification continues to own only the boundary rules that those current-state sources must preserve.

Directory names use lowercase and kebab-case only.

## Architectural approach

The backend is a modular monolith, not a collection of microservices.

The complete backend is packaged and deployed as one application. Functional modules are compile-time code boundaries, not deployment boundaries.

The browser-facing application boundary remains REST/SSE. Kafka, PostgreSQL, and observability infrastructure remain external runtime dependencies rather than reasons to split functional modules into separate deployments.

The current functional-module inventory belongs in [`modules/README.md`](../../../../modules/README.md), not in this supporting specification.

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

Every functional module has an internal implementation surface. It may expose a narrow public contract only when a real synchronous functional-module consumer exists.

If inbound interfaces are used only by the module's own adapters, they should remain internal instead of creating a nominal public API.

When published, the preferred Java namespace convention is:

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

This rule is enforced with an ArchUnit-backed architecture test that rejects production cross-module Java dependencies outside the providing module's `api..` package.

Public module APIs should remain small. A class must not move to an `api` package merely because another module wants convenient access to it.

Published module behavior is expressed through interfaces under `api`. Contract data may be colocated there or referenced from an existing owned model when duplication would add no value.

The following stay internal when they have no external functional-module consumer:

- controller-facing application ports;
- repositories;
- outbound clients;
- publishers;
- analyzers;
- strategy interfaces.

REST controllers remain transport adapters. They are never promoted to Java API merely to make them injectable.

Each functional module also keeps a root `contract.md` as a compact context/navigation index.

It points to authoritative Java `api/**`, OpenAPI, and Protobuf sources. It records ownership, dependency, and invariant information without copying complete method or field signatures.

This supports focused repository navigation without requiring unrelated implementation context.

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

## Requirement map

| Requirement | Feature ID | Purpose |
|---|---|---|
| R1-R7 | `PLATFORM.MODULAR_MONOLITH` | Gradle/module ownership and composition-root boundaries |
| R7a | `RUNTIME.CONCURRENCY` | Netty blocking/streaming execution rules |
| R8 | `PLATFORM.MODULAR_MONOLITH` | Shared-kernel admission boundary |
| R9 | `CONTRACTS.KAFKA_PROTOBUF` | Kafka integration-contract ownership |
| R10 | `CONTRACTS.HTTP` | REST/SSE external contract ownership |
| R11-R14 | `PLATFORM.MODULAR_MONOLITH` | Persistence ownership and architecture-testable dependency direction |
| R15-R17 | `TESTING.DETERMINISTIC_LOCAL` | Java/Testcontainers integration strategy |
| R18 | `PLATFORM.MODULAR_MONOLITH` | Runtime-need-driven service extraction |

## Requirements

### R1 — Gradle multi-project modular monolith

The backend must use Gradle Kotlin DSL.

Each functional module must be a Gradle subproject.

The build must keep explicit subprojects for the composition root, shared kernel, contract sources, functional modules, and testing support where those areas exist. The actual current subproject inventory is authoritative in `settings.gradle.kts`; the current functional-module inventory is authoritative in `modules/README.md`.

Only `app` is the backend executable application.

Functional modules use `java-library` semantics and are not independently deployable applications.

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

REST controller methods or classes that invoke JDBC, blocking source access, or other imperative blocking workflows must use `@ExecuteOn(TaskExecutors.BLOCKING)` or an equivalent explicit blocking boundary.

On Java 21, the blocking executor is Virtual-Thread backed.

Controllers that expose true streaming/reactive responses, especially SSE, must keep their reactive execution model instead of being moved to blocking execution mechanically.

Micronaut client/server filters should remain lightweight and non-blocking. A filter that performs blocking work must explicitly offload it and must not block a Netty event loop.

Expected application/domain failures should be mapped through centralized Micronaut `ExceptionHandler` implementations at the HTTP boundary. Do not duplicate controller `try/catch` translation.

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

The current schema inventory belongs to the event-contract source tree and [`backend-event-contracts.md`](backend-event-contracts.md); this supporting specification does not duplicate that inventory.

The contract module may define:

- a common event envelope;
- integration-event messages;
- schema/package versioning;
- event and correlation identifiers carried on the wire;
- reusable protocol-level value messages that genuinely belong to the integration contract.

Generated Java classes are build output. They must be generated by Gradle from `.proto` definitions and must not be manually maintained as parallel source-of-truth Java DTOs.

The contract module must not contain event handlers, Kafka clients, repositories, application services, or module business logic.

Protobuf-generated message classes are transport types, not domain entities.

A functional module may map a generated event message to its own internal model. Generated classes should not spread through unrelated application logic.

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

REST request/response payloads remain JSON-oriented contracts. Server-Sent Events exposed to the browser also use browser-friendly JSON.

Do not use Protobuf merely to make in-process Java calls or browser APIs more complex.

The frontend and external black-box test clients must depend on the HTTP contract, not Java implementation classes.

### R11 — no cross-module database access

A functional module owns its persistence data.

Initially modules may share one PostgreSQL server and one physical database for operational simplicity.

Logical ownership must nevertheless be explicit.

Each persistence-owning functional module should retain a distinct logical PostgreSQL ownership boundary even when modules share one physical database and Flyway history. Current schema/table ownership belongs in module contracts and `docs/IMPLEMENTATION.md`, not in this supporting specification.

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

`app` may depend on the functional modules it composes. `common` and contract modules remain lower-level dependencies and must not depend on functional implementation modules.

The Collection-to-Configuration dependency is an intentional synchronous functional-module dependency because Collection needs current effective configuration. It must use only Configuration's published Java API. Any additional synchronous functional-module edge requires a concrete ownership reason and must preserve acyclicity.

Processing relationships that are deliberately asynchronous must remain expressed through Kafka contracts rather than direct module calls.

The actual current Gradle dependency graph is derived from module build files and protected by architecture tests; it is not duplicated here as an inventory.

### R14 — module internals should be architecture-testable

The project uses ArchUnit now that real Java package boundaries exist.

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

## Package guidance

A functional module may use a structure similar to:

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

The modular-monolith boundary supersedes the earlier service-first skeleton. The accepted current functional-module topology is documented in `modules/README.md`; this supporting specification does not preserve the historical service-to-module inventory.

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

## Ongoing maintenance responsibilities

This supporting specification no longer maintains an implementation checklist. Current bounded work belongs in the active specification selected by `docs/specs/README.md` and umbrella `current_focus`; accepted implementation truth belongs in current-state documentation.

Future changes must continue to:

- keep the Gradle dependency graph explicit and acyclic;
- preserve the thin composition-root boundary;
- keep synchronous cross-module Java APIs narrow and deliberate;
- keep asynchronous integration on authoritative Kafka/Protobuf contracts;
- preserve module-owned persistence and migration ownership;
- keep architecture tests aligned with published module boundaries;
- keep deterministic integration coverage over the assembled backend and real infrastructure boundaries.
