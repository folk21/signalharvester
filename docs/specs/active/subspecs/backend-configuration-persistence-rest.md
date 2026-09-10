---
type: Specification
title: SignalHarvester source configuration persistence and REST
description: Current implementation sub-specification for PostgreSQL-backed source CRUD, Flyway ownership, blocking REST execution, validation, and provider wiring.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: active
---
# SignalHarvester source configuration persistence and REST

## Status

Active technical sub-specification — current implementation focus.

The configuration public Java API and source OpenAPI contract already exist. This slice turns those contracts into a persisted, runnable capability without expanding into monitoring profiles, scheduling, or collection-to-Kafka publication.

## Goal

Implement the first complete configuration vertical boundary:

```text
REST/OpenAPI
    -> configuration use cases
    -> configuration-owned PostgreSQL persistence
    -> SourceConfigurationProvider
```

The implementation must preserve the modular-monolith dependency rules: configuration owns its data and behavior, while collection consumes only the configuration module's public Java API.

## Relationship to the umbrella specification

This sub-spec advances umbrella requirements R2 and R3 groundwork by making external-source configuration durable and accessible through the existing HTTP contract. It also establishes persistence and controller patterns that later monitoring-profile work can reuse when ownership and duplication are understood.

Source testing itself is not part of this slice; it depends on the collection boundary and should be specified separately when exposed through the API.

## Current state

Implemented today:

- `SourceId`, `SourceType`, `ConfiguredSource`, and `SourceConfigurationProvider` public Java contracts;
- `/api/v1/sources` CRUD operations and source schemas in OpenAPI;
- `ConfiguredSource` invariants for non-blank names and absolute HTTP(S) locations with a host, without embedded credentials or fragments;
- collection depends on the configuration public API only;
- Testcontainers PostgreSQL dependency is available in the integration-test project, but no database-backed test exists yet.

Not implemented today:

- configuration PostgreSQL schema or Flyway migrations;
- source repository/persistence adapter;
- concrete `SourceConfigurationProvider` bean;
- source application use cases;
- REST controller and HTTP exception mapping;
- database/runtime configuration for PostgreSQL;
- server-level tests for source CRUD and blocking execution.

## Requirements

### R1 — configuration owns source persistence

Source tables and Flyway migrations belong to `modules:configuration`. No other functional module may query or mutate those tables directly.

The schema must preserve the public source semantics currently represented by `ConfiguredSource`: stable identifier, name, source type, location, enabled state, and source-specific string settings.

### R2 — public provider is backed by persisted state

`SourceConfigurationProvider` must have a concrete configuration-owned implementation that reads effective source configuration from PostgreSQL.

Collection must continue to compile against the public configuration API rather than a repository, persistence entity, or SQL abstraction.

### R3 — REST implements the existing OpenAPI contract

Implement the existing operations:

```text
GET    /api/v1/sources
POST   /api/v1/sources
GET    /api/v1/sources/{sourceId}
PUT    /api/v1/sources/{sourceId}
DELETE /api/v1/sources/{sourceId}
```

Do not expose persistence entities as HTTP representations. HTTP mapping belongs at the configuration module boundary.

### R4 — validation is consistent across HTTP and Java boundaries

Request validation must reject values that cannot produce a valid `ConfiguredSource`, including:

- blank or whitespace-only names;
- unsupported URI schemes;
- locations without a host;
- embedded URI user-info credentials;
- URI fragments.

Jakarta/Micronaut Validation should enforce HTTP/input constraints where appropriate. Intrinsic invariants remain enforced by the Java value types as the final boundary.

### R5 — expected API failures are mapped centrally

Expected application failures such as source-not-found and invalid configuration must be mapped through configuration-owned or application-level Micronaut exception handlers rather than repeated controller `try/catch` response construction.

HTTP status behavior must remain consistent with the existing OpenAPI responses.

### R6 — blocking controller work is offloaded

Controller paths that execute JDBC/blocking persistence work must use `@ExecuteOn(TaskExecutors.BLOCKING)` at the appropriate boundary.

No JDBC work may run on a Netty event-loop thread. A server-level test must verify this execution property for at least one database-backed source operation.

### R7 — database behavior is deterministic and integration-tested

Use PostgreSQL and Flyway. Database integration tests use Testcontainers and must not require a developer-installed database, public network access, or production credentials.

Tests must cover at least:

- migration/bootstrap from an empty database;
- create/read/list/update/delete source behavior;
- source settings persistence;
- duplicate/not-found behavior according to the chosen explicit application contract;
- rollback/transaction behavior for failed writes where relevant;
- concrete `SourceConfigurationProvider` reads;
- REST validation and status mapping.

### R8 — untrusted outbound access remains closed until policy exists

Persisting a source URL does not by itself authorize unrestricted outbound access to that destination.

Until a configurable outbound destination/SSRF policy is implemented, documentation and API behavior must not claim that arbitrary untrusted user-provided URLs are safe to collect. Loopback/private destinations cannot be blanket-disabled in domain validation because deterministic tests and legitimate internal sources may require them.

## Scenarios

### S1 — create and read a source

1. A client posts a valid source request.
2. The configuration module validates and persists it in its own database objects.
3. The API returns the created source with a stable identifier.
4. A later GET returns equivalent configuration after a fresh database read.
5. `SourceConfigurationProvider` can expose the same effective source to collection without exposing persistence internals.

### S2 — reject invalid source input

1. A client submits a whitespace-only name or invalid source location.
2. The HTTP boundary rejects the request as invalid configuration.
3. No partial source row/settings state is persisted.
4. The Java domain boundary would reject the same invalid effective configuration independently of Micronaut validation.

### S3 — blocking persistence does not run on Netty event loop

1. A server-level test invokes a source CRUD endpoint.
2. The controller/use-case path performs real PostgreSQL I/O.
3. Test instrumentation confirms the blocking work executes on Micronaut's blocking executor rather than an event-loop thread.

## Non-goals

This slice does not implement:

- monitoring profiles or schedules;
- source test/preview endpoint;
- collection triggering;
- Kafka publication;
- transactional outbox for configuration changes without a concrete event requirement;
- analysis/results behavior;
- SSE;
- a final outbound SSRF policy;
- schema sharing across functional modules.

## Design constraints

- Keep persistence types internal to configuration.
- Prefer explicit repository/use-case contracts only where they represent real boundaries; do not create mechanical `Service`/`ServiceImpl` pairs.
- Keep transactions owned by the configuration use case that requires atomicity.
- Do not add configuration tables to `common` or `app`.
- Keep database credentials and endpoints in runtime configuration.
- Use Flyway migrations from the owning module and make migration order deterministic.
- Preserve the existing `SourceId`/`ConfiguredSource` public Java contract unless implementation proves a concrete change is required.
- Preserve the existing OpenAPI paths and compatible response semantics.

## Compatibility / migration

There is no existing persisted production schema to migrate. The first Flyway migration establishes the baseline configuration schema.

The OpenAPI source contract already exists and should be treated as the external compatibility target for this slice. Changes to that contract must be additive/compatible unless a concrete defect requires correction before controller publication.

## Validation

The slice is complete when:

1. a clean PostgreSQL container migrates successfully with configuration-owned Flyway migrations;
2. source CRUD works through the existing REST paths against real PostgreSQL;
3. `SourceConfigurationProvider` reads persisted effective sources;
4. invalid names/locations are rejected consistently by HTTP validation and Java invariants;
5. expected not-found/validation failures are centrally mapped to documented HTTP statuses;
6. server-level testing proves JDBC work is offloaded from Netty event-loop threads;
7. configuration module tests and relevant app/integration tests pass without public network access;
8. current-state documentation is updated and this sub-spec can be archived after stable knowledge is moved to owning docs.

## Implementation tasks

1. Add configuration-owned PostgreSQL/Flyway dependencies and runtime configuration.
2. Add the initial configuration schema migration for sources and source settings.
3. Implement persistence mapping and transaction boundaries.
4. Implement source CRUD use cases and a concrete `SourceConfigurationProvider`.
5. Implement REST mapping for the existing OpenAPI paths.
6. Add centralized exception handling for expected configuration/API failures.
7. Add Jakarta Validation at the REST/input boundary while retaining Java invariants.
8. Add PostgreSQL Testcontainers tests for migration, persistence, and provider behavior.
9. Add Micronaut server-level CRUD tests, including blocking-executor verification.
10. Update owning current-state docs after implementation and archive this sub-spec when accepted.
