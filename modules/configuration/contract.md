---
type: Module Contract
title: SignalHarvester module contract — Configuration
description: Public integration surface, ownership, invariants, and dependency rules for the configuration module.
---
# SignalHarvester module contract — Configuration

## Purpose

Own persisted source and monitoring-profile configuration without leaking persistence or HTTP implementation details.

## Owned responsibilities

- source configuration lifecycle and validation;
- monitoring-profile lifecycle, source membership, interval, criteria, and typed Analysis settings configuration;
- configuration-owned PostgreSQL schema and migrations;
- effective source configuration consumed by Collection;
- Source and Monitoring Profile REST implementation.

## Public integration surface

### Synchronous Java API

Authoritative published package:

`modules/configuration/src/main/java/io/signalharvester/configuration/api/`

Published cross-module interfaces:

- `SourceConfigurationProvider` — narrow source read API consumed by Collection;
- `MonitoringProfileConfigurationProvider` — narrow Monitoring Profile read API for scheduling and profile-scoped execution.

Published contract data remains in the same package because it is part of the provider surface:

- `SourceId`;
- `SourceType`;
- `ConfiguredSource`;
- `MonitoringProfileId`;
- `ConfiguredMonitoringProfile`;
- `MonitoringProfileAnalysisSettings`.

Configuration administration is an internal application boundary under `modules/configuration/src/main/java/io/signalharvester/configuration/application/`; its command/interface types are intentionally not published to other functional modules. Consumers should read the Java files above rather than relying on duplicated method signatures in this document.

### REST / SSE API

Authoritative schema: `contracts/api-contracts/src/main/resources/openapi/signalharvester-v1.yaml`.

Configuration owns Source CRUD routes under `/api/v1/sources` (excluding the Collection-owned `/api/v1/sources/{sourceId}/test` diagnostic route) and Monitoring Profile CRUD routes under `/api/v1/monitoring-profiles`.

Implementation adapters live under `modules/configuration/src/main/java/io/signalharvester/configuration/http/`.

HTTP controllers and HTTP request/response models are transport adapters, not cross-module Java APIs.

### Events

None. Configuration currently owns no Kafka event boundary.

## Owned data

- PostgreSQL schema `configuration`;
- source/source-settings tables plus monitoring-profile, membership, criteria, and Analysis-settings tables created by migrations under `modules/configuration/src/main/resources/db/migration/configuration/`.

Other modules must not query or mutate these tables directly.

## Dependencies

Configuration depends synchronously only on the published `io.signalharvester.operations.api..` change-journal boundary so supported REST/UI mutations can record sanitized operational changes. It must not depend on Operations implementation or persistence types.

Collection may depend on `io.signalharvester.configuration.api..` only.

## Forbidden access

Consumers must not import Configuration `application`, `persistence`, or `http` packages and must not use Configuration-owned database tables directly.

Do not expose repository, Jdbi/JDBC, Micronaut HTTP, or persistence types through the Java API.

## Important invariants

- `SourceId` is the stable source identity;
- `MonitoringProfileId` is the stable Monitoring Profile identity;
- a Monitoring Profile references at least one existing source;
- effective Analysis settings are either explicit all-relevant (`keywords=[]`, `minimumMatches=0`) or normalized unique keywords with a positive threshold no greater than the keyword count;
- create omission persists all-relevant settings; update omission preserves current effective settings; legacy rows without explicit settings resolve compatibility defaults until their next update;
- source names are not unique identities;
- configured-source writes are transactional;
- persisted source URLs do not imply outbound network authorization.

## Extension points

Add new synchronous module capabilities only when another adapter/module has a concrete need. Prefer extending an existing focused API over exposing implementation classes.

Scheduling should consume `MonitoringProfileConfigurationProvider` and must not query Configuration tables directly. Keep scheduler-specific state outside the persisted profile contract.
