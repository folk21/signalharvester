---
type: Module Contract
title: SignalHarvester module contract — Operations
description: Ownership of operational change history, persisted health snapshots/reports, and assisted-investigation boundaries.
---
# SignalHarvester module contract — Operations

## Purpose

Own the durable operational timeline used to correlate behavior-affecting changes with health evidence, plus persisted Health Snapshots and bounded Health Reports.

## Feature ownership

- `OPERATIONS.CHANGE_JOURNAL`
- `OBSERVABILITY.HEALTH_INTELLIGENCE`
- `OBSERVABILITY.ASSISTED_INVESTIGATION`

## Owned responsibilities

- persist sanitized operational change records;
- expose the small synchronous change-journal API used by mutating modules;
- persist and query versioned Health Snapshots;
- render bounded JSON/Markdown Health Reports from persisted evidence;
- expose ADMIN operational-history/health HTTP reads, explicit Health Snapshot capture, and typed tooling/scenario markers;
- own the `operations` PostgreSQL schema and retention/query model;
- build bounded sanitized LLM analysis packages;
- validate and persist structured Incident Assessments from manual or explicitly invoked providers;
- own the provider-neutral `IncidentAnalyst` boundary and optional OpenAI-compatible adapter.

## Public integration surface

### Synchronous Java API

Authoritative published package:

`modules/operations/src/main/java/io/signalharvester/operations/api/`

Published entry point:

- `OperationalChangeJournal` — records sanitized applied/rejected changes supplied by the mutating capability owner.

Published data types define stable change category/source/target/outcome vocabulary and request context. They contain no Micronaut, HTTP, Jdbi, or persistence types.

### REST API

Authoritative schema: `contracts/api-contracts/src/main/resources/openapi/signalharvester-v1.yaml`.

Operations owns ADMIN routes under `/api/v1/admin/operations` for bounded change-history reads, explicit Health Snapshot capture, latest snapshot/report/package export, structured assessment import/listing, explicit provider invocation, and nearest before/after snapshot correlation around one change.

### Events

None.

## Owned data

PostgreSQL schema `operations` owns:

- `operations.change_journal`;
- `operations.health_snapshots`;
- `operations.incident_assessments`.

Other modules must not query or mutate these tables directly.

## Dependencies

Operations depends only on platform/framework libraries and the shared kernel. It must not depend on Configuration, Security, Analysis, Results, Collection, or Event Observation implementation packages.

Mutating functional modules may depend on `io.signalharvester.operations.api..` only.

## Forbidden access

Other modules must not import Operations application, persistence, model, or HTTP packages. The change journal must never receive passwords, tokens, authorization headers, source-setting values classified as secret, or unbounded raw telemetry.

## Important invariants

- applied configuration changes that opt into journaling are persisted in the same database transaction as the owned mutation;
- journal payloads are sanitized by the mutating capability owner before they cross the Operations API;
- a failed journal write must roll back an otherwise-applied transactional configuration mutation;
- rejected mutation journaling is best-effort and must not mask the original rejection;
- Health Snapshot/report generation is auxiliary and must not affect Collection/Analysis/Results correctness;
- health status and score are produced by a versioned configurable deterministic/statistical policy; missing required telemetry must remain explicit as uncertainty/`UNKNOWN` rather than being treated as healthy;
- periodic multi-replica sampling uses a PostgreSQL transaction-scoped advisory lock and freshness check, while Prometheus queries happen outside that transaction;
- successful controlled DLQ recovery markers are best-effort evidence and never turn an already successful replay into an operator-visible replay failure;
- persisted Health Snapshot count is bounded by Operations runtime configuration;
- temporal change/health correlation never asserts causality;
- assisted-investigation packages contain bounded sanitized evidence and explicitly mark telemetry as untrusted input;
- model output cannot change persisted Health Snapshot status/score and is persisted only after bounded schema and evidence-reference validation;
- provider HTTP calls happen outside database transactions and are explicit-only in Stage 3;
- provider credentials are runtime secrets and are never journaled or persisted with assessments.

## Extension points

Future stages may add bounded Prometheus/Loki/Tempo investigation tools, event/periodic trigger policy, alert policy, and later ML evaluation. Keep those behind Operations-owned boundaries instead of coupling mutating modules to observability/model providers.
