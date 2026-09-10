---
type: Changelog
title: SignalHarvester changelog
description: Notable project changes organized by release state, with each change line prefixed by its date.
---
# Changelog

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

Future notable change lines must begin with an ISO calendar date (`YYYY-MM-DD`). Time-of-day is intentionally omitted.
