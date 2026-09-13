---
type: Changelog
title: SignalHarvester changelog
description: Notable project changes organized by release state, with each change line prefixed by its date.
---
# Changelog

## Unreleased — runtime and verification hardening

2026-09-13 — Parameterized local PostgreSQL and Redpanda Compose host ports, credentials, advertised host, and admin endpoint while preserving safe defaults.

2026-09-13 — Added an explicit `.env.example` workflow that can be shared by Compose interpolation and the host-run backend without committing local secrets.

2026-09-13 — Added root `run_checks.sh` as the canonical full verification gate for Gradle checks, Testcontainers integration tests, and FULL-archive validation.

2026-09-13 — Hardened `archive.sh` so a FULL archive cannot be produced without `gradle-wrapper.jar` and verifies that the wrapper is actually present.

2026-09-13 — Synchronized installation, configuration, usage, testing, infrastructure, and active project-structure documentation with the new runtime/reproducibility workflow.

## Unreleased

2026-09-08 — Established the initial SignalHarvester modular-monolith backend skeleton and specification hierarchy.

2026-09-08 — Defined Kafka integration events to use versioned Protocol Buffers contracts while REST/SSE remain JSON-facing contracts.

2026-09-08 — Added the initial repository documentation model, development rules, and companion `signalharvester-ui` project boundary.

2026-09-08 — Bootstrapped the runnable Micronaut application foundation and centralized dependency/plugin versions.

2026-09-08 — Added the first source-configuration Java/OpenAPI contracts and versioned Protobuf event envelope/raw-item schemas with initial contract tests.

2026-09-09 — Refined collection external-source transport to use Micronaut HTTP infrastructure with bounded Virtual Thread execution, explicit HTTP safety/resource limits, stable error metadata, and deterministic loopback/concurrency tests.

2026-09-09 — Corrected collection-module Micronaut build wiring so HTTP client, validation, DI, and Virtual Thread executor APIs are present on the compile/test classpaths.

2026-09-10 — Replaced the fixed-base declarative source client with the Micronaut-managed low-level client so configuration-driven absolute URLs can span arbitrary hosts while retaining Virtual Threads, filters, pooling, timeouts, redirects, and error mapping.

2026-09-10 — Reconciled collection transport, OpenAPI validation, module dependencies, active specifications, tests, and archive tooling with the implemented low-level Micronaut HTTP design.

2026-09-10 — Implemented PostgreSQL/Flyway-backed source configuration CRUD, concrete `SourceConfigurationProvider`, validated blocking REST endpoints, centralized HTTP error mapping, and PostgreSQL Testcontainers coverage.

2026-09-10 — Added the first acknowledged collection-to-Kafka publisher with explicit RawItemDiscovered Protobuf byte serialization, configurable topic/key conventions, correlation metadata, and Kafka Testcontainers round-trip coverage.

2026-09-10 — Added explicit best-effort collection-run orchestration over enabled persisted sources with run correlation, deterministic raw-item identity, per-source terminal outcomes, and cross-module PostgreSQL/HTTP/Kafka integration coverage.

2026-09-10 — Added the first analysis consumer pipeline with deterministic normalization, PostgreSQL-backed profile-scoped deduplication, configurable keyword analysis, manual Kafka offset commit, and `ItemAnalyzed`/`ItemRejected` event publication.

2026-09-11 — Added operational admin REST APIs for manual collection runs, durable run history, and read-only analysis item inspection.

2026-09-11 — Added repository-owned Docker Compose for local PostgreSQL and single-node KRaft Kafka, including health checks and local topic provisioning behavior.

2026-09-11 — Fixed native Kafka local startup by initializing persisted-volume ownership, upgrading the development broker to Kafka 4.2.1, and removing JVM CLI assumptions from native-image health/topic setup.

2026-09-11 — Replaced the broken native-Kafka local Compose setup with a complete PostgreSQL + single-node Redpanda stack while preserving the Kafka protocol contract.

2026-09-12 — Separated integration-tagged Testcontainers/cross-module tests from the default Gradle `test` lifecycle with explicit `integrationTest` tasks.

2026-09-12 — Strengthened test lifecycle isolation by moving container-backed and cross-module scenarios into dedicated `integrationTest` source sets instead of relying on JUnit tags.

2026-09-12 — Standardized published module Java APIs under `api` packages, added module boundary contracts/selective-context guidance, and introduced ArchUnit enforcement for cross-module dependencies.

2026-09-12 — Reconciled current-state documentation and active-spec lifecycle with the implemented REST/admin APIs, persistence, Redpanda infrastructure, dedicated integration-test tasks, and module API boundaries; removed duplicate active copies of archived sub-specifications.

2026-09-12 — Batched collection recent-history persistence reads to eliminate per-run source queries while preserving deterministic run and source ordering.

2026-09-13 — Hardened collection run-history persistence with enforced Micronaut transaction participation, atomicity regression coverage, typed UUID lookups, bounded Java API limits, centralized SQL, and defensive row mapping.

2026-09-13 — Reconciled REST/OpenAPI defaults and validation, made required JSON response fields serialization-stable, and added server-level contract coverage for collection and analysis operational endpoints.

2026-09-13 — Pipelined collection fetch completion into terminal publication with bounded backpressure so raw source payload retention scales with configured concurrency instead of total run size.

2026-09-13 — Hardened analysis transaction/event guarantees with transaction-aware deduplication persistence, rollback coverage for failed terminal publication, poison-input offset protection, and focused analysis Kafka mapping/publication tests.

2026-09-13 — Tightened module API semantics so only real synchronous cross-module contracts remain published, reduced the collection-to-configuration dependency to implementation scope, and expanded architecture enforcement for cycles, API purity, adapter layering, and composition-root direction.

Future notable change lines must begin with an ISO calendar date (`YYYY-MM-DD`). Time-of-day is intentionally omitted.
