---
type: Specification
title: Jdbi persistence refactoring
description: Replace direct JDBC statement plumbing with module-local Jdbi adapters while preserving contracts, schema, transaction ownership, and PostgreSQL semantics.
document_role: subspec
spec_status: completed
parent: ../spec-signal-harvester-platform.md
---
# Jdbi persistence refactoring

## Status

Accepted on 2026-09-18 after the developer confirmed the canonical repository gate passed for the final Results slice and the corrective Security single-handle administrative update path. The migration now defines the accepted runtime SQL execution convention for the backend; future local persistence refactors do not require this specification to remain active.

## Feature scope

This refactoring does not introduce a new product capability or feature ID. It preserves the existing feature contracts owned by the affected modules, beginning with:

- `CONFIGURATION.SOURCES`;
- `CONFIGURATION.MONITORING_PROFILES`;
- `SECURITY.IDENTITY_ROLES`;
- `SECURITY.AUTHENTICATION`;
- `ANALYSIS.DEDUPLICATION`;
- `ANALYSIS.OUTBOX`;
- `DIAGNOSTICS.ANALYSIS_INSPECTION`;
- `COLLECTION.RUNS`;
- `COLLECTION.SCHEDULING`;
- `DIAGNOSTICS.EVENT_OBSERVATION`;
- `RESULTS.MATERIALIZATION`;
- `RESULTS.BROWSING`;
- `RESULTS.LIVE`;
- `TESTING.DETERMINISTIC_LOCAL` for regression protection.

## Goal

Reduce persistence fragility and JDBC boilerplate without introducing a heavyweight ORM or changing database ownership.

Jdbi becomes the lightweight SQL execution/mapping boundary. SQL remains explicit and PostgreSQL-aware. Application services keep transaction ownership. Flyway remains the schema owner.

## Current state

Configuration, Security, Analysis, Collection, Event Observation, and Results use Micronaut-managed Jdbi. Substantial SQL lives in module-owned resources and uses named bindings; Results browse/live queries use StringTemplate 4 only to render trusted structural predicates before named binding. Security keeps its lock/read/check/update sequence on one transaction-bound Jdbi handle, restoring the connection/session scope relied upon before the migration.

The existing architecture already has the correct higher-level boundary: application use cases own Micronaut `TransactionOperations<Connection>` transactions and repositories own persistence details. This refactoring must preserve that boundary.

## Requirements

### R1 — behavioral and contract parity

The refactoring must not intentionally change:

- REST/OpenAPI or SSE contracts;
- Kafka/Protobuf contracts;
- Flyway migrations or persisted schema;
- ordering, filtering, pagination, idempotency, or locking semantics;
- application exception semantics;
- module ownership or dependency direction.

Existing characterization and integration tests remain the primary regression oracle.

### R2 — application-owned transactions

Application use cases must continue to own transaction boundaries through the existing Micronaut transaction infrastructure.

Jdbi operations must participate in those transactions through Micronaut's Jdbi/Data transaction integration. Repository adapters must not introduce independent business transaction boundaries merely because Jdbi exposes transaction APIs. Lock-sensitive multi-statement sequences that relied on one JDBC connection/session before migration must execute on one transaction-bound Jdbi handle.

### R3 — explicit SQL with named bindings

SQL must remain visible and reviewable. Prefer named bind parameters over positional JDBC indexes.

Static or substantial SQL should live in module-owned classpath `.sql` resources when externalization improves readability. Small statements may remain local only when doing so is clearly simpler than creating a resource. Resolve those resources through the single shared `SqlResources` utility in `common`; persistence adapters supply their module-owned SQL directory and statement name. Do not create module-specific SQL locator/facade classes.

SQL values must use bind parameters. SQL templating must not interpolate untrusted values as SQL syntax.

### R4 — templating only for structural SQL variation

StringTemplate 4 may be introduced through Jdbi's supported integration when a module has genuine structural query variation that would otherwise require fragile string assembly.

Do not use a template engine for static SQL or simple value binding. The first Configuration pilot therefore uses plain classpath SQL resources without StringTemplate.

Results browsing is the justified use in this refactoring because it combines optional predicates, PostgreSQL full-text search, deterministic ordering, and keyset continuation. Its template attributes only select trusted static predicate blocks; request values remain named bindings.

### R5 — incremental module migration

Migrate persistence in this order unless review finds a concrete dependency reason to adjust it:

1. Configuration pilot;
2. Security, Analysis, and Collection in bounded slices that may be combined when their persistence patterns are simple and independent;
3. Event Observation;
4. Results last, after explicitly reviewing dynamic browsing and live/projection SQL.

Do not run two competing persistence paradigms inside one migrated adapter. Temporary repository-wide coexistence of direct JDBC and Jdbi is allowed only during this staged migration.

### R6 — mapping stays module-local

Jdbi row/column mapping and SQL resources remain implementation details of the owning module. They must not leak into published `api` packages or application/domain contracts.

Generated ORM entities or a shared cross-module persistence layer are out of scope.

### R7 — regression protection

Each migrated module must preserve or strengthen tests for:

- transaction rollback;
- deterministic read ordering;
- not-found/update/delete semantics;
- batch/child replacement behavior where applicable;
- PostgreSQL-specific behavior relied upon by the module;
- restart/durability behavior where already covered.

The canonical repository gate must pass before a migration slice is accepted.

## Non-goals

- Hibernate/JPA or another heavyweight ORM;
- Micronaut Data repository/entity migration;
- schema redesign or migration cleanup;
- changing module APIs or business transaction boundaries;
- hiding PostgreSQL-specific features behind a database-neutral abstraction;
- introducing StringTemplate where static SQL is sufficient;
- changing Results browsing semantics while migrating its SQL implementation.

## Configuration pilot

The first slice must:

1. add Micronaut Jdbi integration to the Configuration module;
2. replace direct `Connection`/`PreparedStatement` lifecycle management and manual `ResultSet` iteration in Source and Monitoring Profile repositories with Jdbi; focused Jdbi row-mapper callbacks may still read JDBC `ResultSet` values where explicit domain conversion is clearer than reflective mapping;
3. move Configuration-owned SQL into classpath `.sql` resources;
4. use named bindings and Jdbi batches for child collections;
5. preserve application-owned transaction rollback through existing integration tests;
6. keep Analysis-settings legacy fallback behavior unchanged.

## Event Observation slice

The Event Observation slice must:

1. add Micronaut Jdbi integration to the module;
2. replace direct JDBC statement/result-set lifecycle management with a module-local Jdbi repository adapter;
3. move insert, retention, cursor, recent-history, and live-cursor SQL into classpath `.sql` resources;
4. remove Java `StringBuilder`/positional-parameter query assembly by expressing optional bounded filters as typed nullable named criteria in static SQL;
5. preserve idempotent event identity, retention ordering, SSE cursor ordering, and Processing Flow query semantics;
6. require repository access to participate in the existing application-owned transaction boundary;
7. keep StringTemplate 4 out of this slice because static SQL can express all current structural variants clearly.

## Results slice

The final Results slice must:

1. add the existing shared `SqlResources`/Micronaut Jdbi pattern to Results without creating a Results-specific SQL locator;
2. replace direct JDBC projection and read adapters with Jdbi adapters that require the existing application-owned transaction;
3. move projection, detail, cursor, browsing, and live-query SQL into Results-owned classpath `.sql` resources with named bindings;
4. use Jdbi StringTemplate 4 only for `find-page` and `find-live-updates`, where structural predicate omission keeps the executed PostgreSQL query direct and index-friendly;
5. keep all template conditions controlled by application criteria presence, never by user-provided SQL fragments, identifiers, or syntax;
6. preserve deterministic keyset ordering, PostgreSQL full-text search, durable live-cursor/idempotency semantics, and explicit Results-local row mapping;
7. strengthen regression coverage for direct repository access outside application transactions and rollback of the complete analyzed projection when a child write fails.

## Validation

The Configuration, Security / Analysis / Collection, Event Observation, and Results slices passed the canonical repository gate in the developer environment. Final verification included the corrective Security change that keeps administrator lock/read/check/write work on one transaction-bound Jdbi handle, Results browsing/live/projection regression coverage, transaction ownership checks, and projection rollback coverage.

## Implementation tasks

1. Configuration pilot — completed and verified.
2. Jdbi convention review — completed; retain explicit row mapping, classpath SQL resources, named bindings, and application-owned transactions without a shared persistence abstraction.
3. Security / Analysis / Collection migration — completed and verified.
4. Event Observation migration — completed and verified.
5. Results dynamic-SQL review and migration — completed and verified with bounded StringTemplate 4 structural rendering.
6. Security handle-lifecycle parity correction — completed and verified; administrator lock/read/check/write work stays on one transaction-bound Jdbi handle.
7. Stable persistence conventions were moved into owning architecture/implementation documentation and this specification was archived after the canonical repository gate passed.
