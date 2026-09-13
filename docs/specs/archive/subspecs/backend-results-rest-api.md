---
type: Specification
title: SignalHarvester backend Results REST API
description: Completed sub-specification for browsing persisted analyzed results through a bounded public REST read API.
document_role: subspec
parent: ../../active/spec-signal-harvester-platform.md
spec_status: completed
---
# SignalHarvester backend Results REST API

## Status

Completed and verified in the developer environment. Results persistence is exposed through a bounded public REST contract suitable for frontend browsing and black-box product verification.

## Goal

Provide a stable read-only HTTP boundary over Results-owned PostgreSQL state without leaking JDBC rows, Analysis implementation types, or generated Kafka contract classes.

## Relationship to the umbrella specification

This slice implements the REST portion of umbrella requirements R12 and R15 for persisted result retrieval and inspection. SSE/live-feed delivery remains a later slice.

## Current state

`modules:results` already consumes `ItemAnalyzed` and `ItemRejected` and materializes idempotent PostgreSQL projections. Users can currently inspect terminal Results state only by querying the database directly.

## Requirements

### R1 — OpenAPI is the public contract

The result read endpoints and response schemas must be defined under `contracts/api-contracts` before implementation details are considered public.

### R2 — bounded result feed

`GET /api/v1/results` returns recent analyzed results newest first with a maximum limit of 200. The feed must support filtering by monitoring profile, source, information category, relevance, analyzed time range, and classification.

### R3 — compact list representation

The feed returns the information required for browsing—title, source identity, URL, relevance/classification, score, tags, explanation, analyzer, and timestamps—without returning the potentially large normalized content for every row.

### R4 — profile-scoped detail lookup

`GET /api/v1/results/{normalizedItemId}?monitoringProfileId=...` returns one detailed materialized result including normalized content, attributes, tags, and event/correlation provenance. Missing state returns HTTP 404.

### R5 — Results owns the read model

The HTTP adapter uses Results-owned application/query types and Results-owned persistence only. It must not query Analysis tables or expose Protobuf/JDBC types.

### R6 — read transactions and bounded SQL

The application query service owns short read-only JDBC transactions. Feed retrieval must avoid per-result N+1 queries and preserve deterministic ordering.

### R7 — rejected outcomes remain operational state

This slice exposes analyzed results only. Rejected-result browsing and SSE remain out of scope unless a concrete product/operational consumer requires them.

## Non-goals

- cursor/page-number pagination beyond the bounded recent-result limit;
- full-text search;
- SSE/live updates;
- mutation of Results state through HTTP;
- rejected-result REST endpoints.

## Compatibility / migration

The change is additive. No Flyway migration or Kafka/Protobuf contract change is required because the API reads the existing `results` schema.

## Validation

The slice is ready for acceptance when:

1. controller tests verify defaults, filters, validation, JSON shape, 404 mapping, and blocking-thread offload;
2. PostgreSQL integration coverage verifies filtering, ordering, limit behavior, ordered tags, attributes, and detail lookup;
3. OpenAPI describes the implemented endpoints and nullable fields accurately;
4. `./run_checks.sh` passes in the developer environment.

## Implementation tasks

1. Add Results query application models/boundary/service.
2. Add transaction-aware PostgreSQL query adapter.
3. Add compact feed and detailed REST DTOs/controller.
4. Extend OpenAPI with Results paths and schemas.
5. Add server-level and PostgreSQL integration coverage.
6. Update current-state documentation and archive the completed Results-persistence spec.
