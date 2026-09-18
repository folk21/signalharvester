---
type: Changelog
title: SignalHarvester changelog
description: Notable project changes organized by release state, with each change line prefixed by its date.
---
# Changelog

## Unreleased

- 2026-09-18 — Accepted the repository-wide Jdbi persistence refactoring after the final Results slice and Security single-handle lock correction passed the canonical repository gate; stable persistence conventions are now documented and the implementation spec is archived.
- 2026-09-18 — Migrated the final verification-pending Results persistence slice to Jdbi, externalized projection/query SQL, used StringTemplate 4 only for structural browse/live predicates, and added transaction-ownership plus rollback regression coverage.
- 2026-09-18 — Accepted the Security / Analysis / Collection Jdbi slice after correcting nullable Collection batch typing and bounded Security role replacement, then passing the canonical repository gate.
- 2026-09-18 — Migrated Event Observation persistence to verified Jdbi adapters with module-owned SQL resources, named criteria bindings, static bounded history/live queries, and explicit application-owned transaction enforcement.
- 2026-09-18 — Extended the verification-pending Jdbi persistence migration through Security, Analysis, and Collection with named bindings, module-owned SQL resources, transaction-ownership guards, Jdbi batches, and safe bounded list binding.
- 2026-09-18 — Started the staged Jdbi persistence refactoring with a Configuration-module pilot using Micronaut-managed Jdbi, named bindings, classpath SQL resources, and preserved application-owned transactions.
- 2026-09-18 — Accepted controlled ADMIN dead-letter recovery after the developer confirmed the canonical repository gate passed.
- 2026-09-18 — Added verification-pending ADMIN-only controlled dead-letter inspection/replay for Analysis, Results, and Event Observation using real DLQ positions, explicit id confirmation, owner-local decode/application paths, bounded concurrency, and no shared-topic republish.
- 2026-09-18 — Accepted production-oriented Results browsing after the canonical repository gate passed.
- 2026-09-18 — Added verification-pending production-oriented Results browsing with criteria-bound keyset cursors, indexed PostgreSQL text search, additive next-page response metadata, and backward-compatible array responses.
- 2026-09-18 — Accepted profile-owned typed Analysis settings after the canonical repository gate passed in the developer environment.
- 2026-09-17 — Added verification-pending profile-owned typed Analysis settings with PostgreSQL persistence, REST/OpenAPI round-trip, immutable `RawItemDiscovered` snapshots, legacy compatibility, stateless keyword analysis, and focused regression coverage.
- 2026-09-17 — Accepted `SCALABILITY.KAFKA_CONSUMERS` after live Kubernetes verification scaled the backend from one to three replicas, observed three distinct Analysis members owning partitions 0/1/2, drained Analysis lag from 5745 to 0, and completed the canonical repository gate.
- 2026-09-17 — Added live Kafka consumer horizontal-scaling acceptance that builds deterministic backlog, scales the modular-monolith backend from one to three replicas, verifies shared-group partition ownership, backlog drain, persistence completeness, outbox completion, and DLQ stability.
- 2026-09-17 — Accepted the backend Kubernetes/observability deployment and system resilience stages after the developer completed the routine and live-cluster verification workflows.
- 2026-09-16 — Added an opt-in Kubernetes resilience acceptance harness for backend restart, PostgreSQL retry/DLQ, Kafka lag recovery, Analysis outbox recovery, multi-replica scheduler leases, Redpanda restart recovery, authorization boundaries, and observability evidence.
- 2026-09-16 — Added a verification-pending Kubernetes backend/infrastructure stack with non-root backend packaging, PostgreSQL/Redpanda, explicit topic provisioning, Prometheus/Loki/Tempo/Grafana observability, Alloy log collection, kube-state-metrics, provisioned dashboards, and deterministic/live deployment verification tooling.
- 2026-09-16 — Accepted external-source destination authorization after the developer repository gate passed.
- 2026-09-16 — Accepted backend authentication/authorization after developer verification passed.
- 2026-09-16 — Added verification-pending external-source destination authorization with connection-bound DNS validation, redirect revalidation, mixed-answer rejection, secure CIDR allow rules, and trusted-local compatibility.
- 2026-09-16 — Hardened security administration against last-enabled-ADMIN lockout, expanded JWT/RBAC/CORS verification, isolated processing-flow reconstruction, reduced Event Observation retention writes, and added Analysis outbox post-ACK recovery coverage.
- 2026-09-16 — Defined the active `SECURITY.EXTERNAL_SOURCE_ACCESS` specification for production-safe destination authorization, redirect revalidation, DNS binding, and explicit trusted-local compatibility before shared Kubernetes exposure.
- 2026-09-15 — Added persisted human/system identities with additive roles, stateless signed-JWT authentication, HttpOnly browser cookies, CSRF/CORS protection, ADMIN user management, and backend-enforced Results/admin RBAC.
- 2026-09-15 — Added application health/readiness and Prometheus endpoints, OpenTelemetry HTTP/Kafka/JDBC tracing, trace-correlated logs, low-cardinality Collection/Analysis metrics, and trace continuity across Collection fan-out and the Analysis outbox.
- 2026-09-15 — Added a stable uppercase feature-ID catalog, clarified feature/requirement/specification lifecycles, improved active-spec navigation/readability, and archived the verified Analysis transactional-outbox slice.
- 2026-09-15 — Added an Analysis-owned transactional outbox with atomic deduplication/event staging, lease-based multi-replica Kafka dispatch, stable replay identity, and publication retry metadata.
- 2026-09-15 — Accepted bounded Kafka retry/DLQ handling and processing-flow reconstruction after the developer repository checks passed.
- 2026-09-15 — Added bounded retry and versioned dead-letter handling for Analysis, Results, and Event Observation Kafka consumers, with poison-record partition recovery and offset commits only after successful processing or acknowledged DLQ publication.
- 2026-09-14 — Serialized the repository-wide container-backed `integrationTest` Gradle phase to avoid Docker resource contention and Testcontainers readiness timeouts while keeping normal `clean check` parallelism enabled.
- 2026-09-14 — Added bounded processing-flow reconstruction for collection runs and run-scoped items, with explicit evidence levels, stable lineage by `sourceEventId`, diagnostic graph metadata, and public REST/OpenAPI coverage.
- 2026-09-14 — Added bounded event-observation persistence over published raw/analyzed/rejected Kafka events, with decoded diagnostics, retention, REST filtering, and resumable Event Explorer SSE.
- 2026-09-14 — Added Results-owned resumable SSE live delivery with durable PostgreSQL cursors, `Last-Event-ID` reconnection, keepalives, and cross-module pipeline coverage.
- 2026-09-14 — Added persisted-source diagnostic testing plus configuration-driven REST/JSON Pointer and HTML CSS-selector extraction with bounded previews and candidate limits.
- 2026-09-14 — Added profile-driven manual collection and cluster-safe interval scheduling with collection-owned PostgreSQL leases, lease heartbeats, and UUID-only manual-run requests.
- 2026-09-14 — Added persisted monitoring-profile configuration with ordered source membership, collection intervals, criteria, REST/OpenAPI CRUD, source-reference protection, and focused regression coverage.
- 2026-09-13 — Added bounded collection-owned RSS/Atom entry extraction, per-item raw-event publication, extraction-aware run history statuses, and opt-in live-backend verification including a deterministic local two-entry RSS fixture.
- 2026-09-13 — Added the public Results REST API with bounded feed filters, compact list responses, profile-scoped detail reads, and PostgreSQL/controller regression coverage.

- 2026-09-13 — Added Results-owned idempotent PostgreSQL materialization of `ItemAnalyzed` and `ItemRejected`, with manual Kafka offset commit after durable persistence and real Kafka/PostgreSQL integration coverage.
- 2026-09-13 — Added a versioned Python source-manifest importer that bootstraps missing source configuration idempotently through the public REST API, with dry-run/fail-fast modes and deterministic regression coverage.
- 2026-09-13 — Split routine and rare verification: `run_checks.sh` keeps correctness/integration/archive checks, while `run_rare_checks.sh` adds JaCoCo, SpotBugs, dependency analysis, and project-size metrics.
- 2026-09-13 — Added reusable project-size metrics tooling with SignalHarvester-specific classification and text/JSON reports.
- 2026-09-13 — Added repository-wide JaCoCo aggregate coverage, SpotBugs production static-analysis reports, and Gradle dependency-health analysis to the periodic quality-verification workflow.
- Improve `run_checks.sh` diagnostics for FULL archive validation by persisting offending archive entries in `build/reports/verification/archive-cleanliness.txt` and separating non-Gradle reports from Gradle report locations.

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

Future notable change lines must begin with an ISO calendar date (`YYYY-MM-DD`). Time-of-day is intentionally omitted.
