---
type: Module Overview
title: SignalHarvester configuration module
description: Current implementation and developer entry point for persisted SignalHarvester configuration.
---
# SignalHarvester configuration module

For module ownership, published Java APIs, data ownership, dependency rules, invariants, and extension points, read [`contract.md`](contract.md) first.

## Current implementation

Source configuration is persisted in the configuration-owned PostgreSQL schema through an explicit JDBC repository. `SourceConfigurationManager` owns write transaction boundaries, while the repository owns SQL and JDBC resource handling. Flyway migration `db/migration/configuration/V1__create_source_configuration.sql` creates `configuration.sources` and `configuration.source_settings`.

The OpenAPI source-management contract is implemented under `/api/v1/sources`. HTTP request records are separate from persistence types, Jakarta Validation enforces the input boundary, expected not-found/invalid-configuration failures use centralized Micronaut `ExceptionHandler` beans, and the controller runs on `TaskExecutors.BLOCKING` for JDBC work.

Collection consumes effective source configuration through the published configuration API rather than through persistence. Monitoring profiles, schedules, filters, extraction settings, and analysis settings remain planned configuration capabilities rather than completed persistence surfaces.

## Operational and security notes

Persisting a URL is not outbound authorization. Source management remains trusted until a configurable outbound destination/SSRF policy exists; loopback/private destinations are intentionally not rejected by the source domain because deterministic tests and legitimate internal sources may require them.

## Read next

- [`contract.md`](contract.md) — authoritative module boundary and integration map
- [`../AGENTS.md`](../AGENTS.md) — shared module-development rules
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) — system architecture
- [`../../docs/specs/active/subspecs/backend-configuration-persistence-rest.md`](../../docs/specs/active/subspecs/backend-configuration-persistence-rest.md) — active intended changes/acceptance criteria
