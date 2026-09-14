---
type: Specification
title: Backend monitoring profile configuration
description: Persist monitoring profiles, source membership, intervals, and matching criteria behind configuration-owned APIs and REST.
document_role: subspec
spec_status: verification-pending
parent: ../spec-signal-harvester-platform.md
---
# Backend monitoring profile configuration

## Status

Verification pending — implementation is present in the current patch, but repository checks could not run in this environment because Docker is unavailable and the Gradle distribution is not cached.

## Goal

Persist monitoring profiles as configuration-owned application state and expose them through explicit Java and REST contracts.

This slice prepares scheduling and profile-scoped collection. It does not start scheduled runs yet.

## Relationship to the umbrella specification

This slice advances R1 and prepares R4 and R14. It also replaces the previous assumption that profile configuration can remain caller-supplied metadata indefinitely.

## Current state

Source configuration is already persisted and exposed through `/api/v1/sources`. Collection events and persisted results already carry monitoring-profile identity, but the profile itself was not persisted.

## Requirements

### R1 — configuration owns monitoring profiles

A monitoring profile has a stable UUID and stores:

- name;
- information category;
- enabled state;
- collection interval in minutes;
- one or more source identifiers;
- string matching criteria.

### R2 — source membership is explicit

Profile-to-source membership is persisted in deterministic order. A profile cannot reference a source that does not exist at the time of create or update.

### R3 — configuration exposes a narrow Java read API

Other backend modules may read effective profiles only through `io.signalharvester.configuration.api`.

Persistence and HTTP types must remain private to the configuration module.

### R4 — REST CRUD is explicit

The backend exposes CRUD operations under `/api/v1/monitoring-profiles`.

The OpenAPI contract is authoritative for browser clients.

### R5 — persistence remains configuration-owned

Monitoring profile tables live in PostgreSQL schema `configuration` and use the shared Flyway history.

## Non-goals

- automatic scheduling;
- cluster-safe scheduler leases;
- changing manual collection-run semantics;
- profile-owned analysis execution;
- frontend implementation.

## Validation

The slice is ready for acceptance when:

1. unit tests cover intrinsic profile invariants;
2. PostgreSQL integration tests cover CRUD, source membership, and restart durability;
3. REST integration tests cover CRUD and validation;
4. OpenAPI validation passes;
5. `./run_checks.sh` passes in the developer environment.

## Implementation tasks

1. Add the profile domain/API types.
2. Add configuration-owned PostgreSQL persistence.
3. Add application CRUD and source-reference validation.
4. Add REST models/controllers and OpenAPI schemas.
5. Add focused unit and integration coverage.
6. Synchronize configuration documentation after verification.
