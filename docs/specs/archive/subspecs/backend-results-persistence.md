---
type: Specification
title: SignalHarvester backend results persistence
description: Active sub-specification for consuming terminal analysis events into an idempotent Results-owned PostgreSQL projection.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: completed
---
# SignalHarvester backend results persistence

## Status

Completed implementation slice, verified in the developer environment. This sub-specification introduces the first durable Results projection from existing `ItemAnalyzed` and `ItemRejected` Kafka contracts. Result query REST/SSE remains a subsequent slice.

## Goal

Materialize terminal Analysis events into Results-owned PostgreSQL state without coupling Results to Analysis implementation or tables, while preserving at-least-once Kafka retry safety.

## Relationship to the umbrella specification

This implements the first persistence half of the umbrella result/read-model requirement and the `backend-project-structure` Results ownership boundary. It consumes the existing version-one Analysis event contracts without changing their Protobuf schemas.

## Current state

Analysis already publishes `ItemAnalyzed` and `ItemRejected` bytes with manual input-offset commit. An acknowledged Analysis publication followed by Analysis transaction failure can be republished after redelivery, so Results must not create duplicate domain rows from repeated terminal publications.

## Requirements

### R1 — Results owns its persistence

Results owns PostgreSQL schema `results` and migration `V4`. Other functional modules must not write Results tables directly.

### R2 — asynchronous integration only

Results consumes `ItemAnalyzed` and `ItemRejected` from their existing versioned Kafka topics. It must not import Analysis internal Java packages or read Analysis tables.

### R3 — analyzed projection is logically idempotent

`ItemAnalyzed` materializes one current result per `(monitoringProfileId, normalizedItemId)`. Repeated publications for the same logical result update the projection rather than creating duplicate result rows.

### R4 — rejection persistence preserves rediscovery identity

`ItemRejected` persistence is keyed by the upstream `sourceEventId`. Re-delivery or re-publication for the same raw source event updates one rejection row, while a later independent rediscovery with a different source event remains distinguishable.

### R5 — offset commit follows durable persistence

Results disables automatic Kafka offset commit. The consumed offset is committed only after the Results-owned JDBC transaction returns successfully. Invalid payloads, key mismatches, and persistence failures must leave the input offset uncommitted.

### R6 — generated transport types stay at the adapter boundary

Generated Protobuf messages are mapped immediately into Results-owned immutable models. Persistence and application logic must not depend on generated transport classes.

### R7 — read API is explicitly out of scope

This slice does not add REST/SSE result queries, pagination, filters, or UI contracts. Those consume the persisted projection in the next Results slice.

## Compatibility / migration

The slice is additive. Existing Analysis Protobuf schemas and topics are unchanged. Flyway `V4` is globally ordered after configuration `V1`, analysis `V2`, and collection `V3` because the application currently shares one Flyway schema history.

## Validation

The slice is ready for acceptance when:

1. Results unit tests verify analyzed/rejected mapping, Kafka key validation, poison-input no-commit behavior, and persistence-failure no-commit behavior;
2. PostgreSQL + Kafka integration coverage proves real listener materialization for both terminal event types;
3. repeated analyzed publication keeps one logical result row and replaces its child attributes/tags transactionally;
4. repeated rejection publication for one source event keeps one rejection row;
5. `./run_checks.sh` passes in the developer environment.

## Implementation tasks

1. Add Results Micronaut/Kafka/JDBC/Flyway module wiring.
2. Add Results-owned immutable projection models and internal application/persistence ports.
3. Add `V4__create_result_projections.sql`.
4. Add terminal Analysis Kafka listener with manual offset commit.
5. Add idempotent JDBC projection implementation.
6. Add focused unit and container-backed integration tests.
7. Update current-state documentation after implementation.
