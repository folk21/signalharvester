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
- effective source configuration consumed by collection;
- source-management REST implementation.

## Public integration surface

### Synchronous Java API

Authoritative published package:

`src/main/java/io/signalharvester/configuration/api/`

Published cross-module interface:

- `SourceConfigurationProvider` — narrow source read API consumed by collection;
- `MonitoringProfileConfigurationProvider` — narrow monitoring-profile read API for scheduling and profile-scoped execution.

Published contract data remains in the same package because it is part of the provider surface:

- `SourceId`;
- `SourceType`;
- `ConfiguredSource`;
- `MonitoringProfileId`;
- `ConfiguredMonitoringProfile`;
- `MonitoringProfileAnalysisSettings`.

Configuration administration is an internal application boundary under `configuration.application`; its command/interface types are intentionally not published to other functional modules. Consumers should read the Java files above rather than relying on duplicated method signatures in this document.

### REST API

Authoritative schema: `contracts/api-contracts/`.

Implementation adapter: `src/main/java/io/signalharvester/configuration/http/`.

HTTP controllers and HTTP request/response models are transport adapters, not cross-module Java APIs.

### Events

No configuration event contract is currently implemented.

## Owned data

- PostgreSQL schema `configuration`;
- source/source-settings tables plus monitoring-profile, membership, criteria, and Analysis-settings tables created by configuration-owned Flyway migrations.

Other modules must not query or mutate these tables directly.

## Dependencies

The module does not synchronously depend on another functional module.

Collection may depend on `io.signalharvester.configuration.api..` only.

## Forbidden access

Consumers must not import configuration `application`, `persistence`, or `http` packages and must not use configuration-owned database tables directly.

Do not expose repository, Jdbi/JDBC, Micronaut HTTP, or persistence types through the Java API.

## Important invariants

- `SourceId` is the stable source identity;
- `MonitoringProfileId` is the stable monitoring-profile identity;
- a monitoring profile references at least one existing source;
- effective Analysis settings contain normalized unique keywords and a positive threshold no greater than the keyword count;
- new/updated profiles persist effective Analysis settings; legacy rows without explicit settings resolve compatibility defaults until their next update;
- source names are not unique identities;
- configured-source writes are transactional;
- persisted source URLs do not imply outbound network authorization.

## Extension points

Add new synchronous module capabilities only when another adapter/module has a concrete need. Prefer extending an existing focused API over exposing implementation classes.

Scheduling should consume `MonitoringProfileConfigurationProvider` and must not query configuration tables directly. Keep scheduler-specific state outside the persisted profile contract.
