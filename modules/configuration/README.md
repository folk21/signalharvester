---
type: Module Overview
title: SignalHarvester configuration module
description: Persisted monitoring-profile, source, schedule, filter, and analysis configuration ownership.
---
# SignalHarvester configuration module

## Ownership

Own monitoring profiles, source definitions, schedules, filters, extraction settings, analysis settings, and their persistence.

## Boundary

Other modules consume effective configuration through a public Java API or event flow; they do not query configuration tables directly.

## Current state

The public Java boundary under `io.signalharvester.configuration.api` exposes the narrow cross-module `SourceConfigurationProvider`, the administration `SourceConfigurationOperations`, and the small source contract data set (`SourceId`, `SourceType`, `ConfiguredSource`, `SourceConfigurationCommand`).

Source configuration is persisted in the configuration-owned PostgreSQL schema through an explicit JDBC repository. `SourceConfigurationManager` owns write transaction boundaries; the repository owns SQL and JDBC resources. Flyway migration `db/migration/configuration/V1__create_source_configuration.sql` creates `configuration.sources` and `configuration.source_settings`. `SourceConfigurationManager` owns CRUD behavior and implements both published interfaces; collection consumes only the narrower `SourceConfigurationProvider`, while the REST adapter depends on `SourceConfigurationOperations`.

The existing OpenAPI CRUD contract is implemented under `/api/v1/sources`. HTTP request records are separate from persistence types, Jakarta Validation enforces the input boundary, expected not-found/invalid-configuration failures use centralized Micronaut `ExceptionHandler` beans, and the controller runs on `TaskExecutors.BLOCKING` for JDBC work.

Persisting a URL is not outbound authorization. Source management remains trusted until a configurable outbound destination/SSRF policy exists; loopback/private destinations are intentionally not rejected by the source domain because deterministic tests and legitimate internal sources may require them.

See [`contract.md`](contract.md) for the compact integration/context map.

## Read next

- [`../AGENTS.md`](../AGENTS.md)
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md)
- [`../../docs/specs/active/subspecs/backend-configuration-persistence-rest.md`](../../docs/specs/active/subspecs/backend-configuration-persistence-rest.md)
