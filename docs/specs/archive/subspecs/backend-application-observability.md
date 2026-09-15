---
type: Specification
title: Backend application observability
description: Add health probes, Prometheus metrics, OpenTelemetry tracing, log correlation, and trace continuity across custom asynchronous boundaries.
document_role: subspec
spec_status: completed
parent: ../spec-signal-harvester-platform.md
---
# Backend application observability

## Status

Accepted after developer verification.

## Feature scope

Primary feature: `OBSERVABILITY.APPLICATION`.

Related features:

- `EVENTING.CORRELATION` — trace identity must remain correlatable with collection/item flows;
- `ANALYSIS.OUTBOX` — trace context must survive durable staging before Kafka publication;
- `RUNTIME.CONCURRENCY` — custom Virtual-Thread fan-out must not silently discard propagated context.

## Goal

Make the running backend observable through standard operational signals without replacing Event Explorer or Processing Flow.

The application must expose health/readiness and Prometheus metrics locally, emit trace-correlated logs, and support OpenTelemetry trace export when an OTLP collector is configured.

## Requirements

### O1 — operational endpoints

Feature: `OBSERVABILITY.APPLICATION`.

The backend must expose:

- `/health`;
- `/health/liveness`;
- `/health/readiness`;
- `/prometheus`.

The endpoints must be usable without PostgreSQL/Kafka in an infrastructure-disabled application-context test.

### O2 — metrics

Feature: `OBSERVABILITY.APPLICATION`.

Micrometer/Prometheus metrics must include framework/runtime metrics plus low-cardinality application metrics for:

- Collection Run terminal status and duration;
- external source fetch outcome and duration;
- Analysis item terminal status and processing duration;
- Analysis outbox publication outcome.

Entity identifiers such as source IDs, profile IDs, run IDs, item IDs, event IDs, and URLs must not be metric labels.

### O3 — tracing

Feature: `OBSERVABILITY.APPLICATION`.

OpenTelemetry must instrument HTTP server/client, Kafka, and JDBC boundaries. OTLP trace export must remain disabled by default and be enabled only by configuration.

Health and Prometheus scraping endpoints should be excluded from normal HTTP tracing noise.

### O4 — custom context propagation

Features: `OBSERVABILITY.APPLICATION`, `RUNTIME.CONCURRENCY`.

Collection source fetch tasks submitted to the custom blocking-executor fan-out must capture Micronaut `PropagatedContext` so child HTTP spans remain connected to the Collection Run trace.

Every manual or scheduled Collection Run must have an application span. The W3C `traceparent` value placed into `RawItemDiscovered` must use the active run trace when the caller did not already supply one.

### O5 — transactional outbox trace continuity

Features: `OBSERVABILITY.APPLICATION`, `ANALYSIS.OUTBOX`.

Analysis outbox staging must persist the active W3C `traceparent` with the exact terminal-event bytes in the same PostgreSQL transaction.

The dispatcher must restore that parent context before Kafka publication. A retry of the same outbox row must preserve its stored trace context and event identity.

### O6 — log correlation

Feature: `OBSERVABILITY.APPLICATION`.

Console logs must include OpenTelemetry `trace_id` and `span_id` MDC fields when a valid span is current. Logging must remain stdout/stderr oriented; this stage does not add application-owned rotating log files.

## Failure semantics

Observability must not become a correctness dependency.

- Missing metric or OpenTelemetry beans degrade to no-op application instrumentation.
- OTLP collector unavailability must not be required for normal local startup when trace export is disabled.
- Business processing must not fail merely because telemetry export is disabled.

## Non-goals

This stage does not add:

- Prometheus, Tempo, Loki, Grafana, or OpenTelemetry Collector deployment;
- Kubernetes manifests;
- custom infrastructure dashboards;
- frontend telemetry;
- authentication/authorization for management endpoints.

The security stage must revisit management-endpoint exposure before public/shared deployment.

## Validation

Acceptance requires:

- Collection and Analysis observability unit tests;
- application server test for liveness/readiness/Prometheus endpoints;
- Analysis PostgreSQL integration coverage for persisted outbox trace context;
- normal unit/integration/architecture verification;
- `./run_checks.sh` passing in the developer environment.
