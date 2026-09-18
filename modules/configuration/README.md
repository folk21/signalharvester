---
type: Module Overview
title: SignalHarvester configuration module
description: Current implementation and developer entry point for persisted SignalHarvester configuration.
---
# SignalHarvester configuration module

For module ownership, published Java APIs, data ownership, dependency rules, invariants, and extension points, read [`contract.md`](contract.md) first.

## Current implementation

Source configuration is persisted in the configuration-owned PostgreSQL schema through Jdbi repositories. Application managers continue to own write transaction boundaries through Micronaut transactions, while Jdbi participates in those transactions and owns SQL execution/mapping details. Static Configuration SQL lives in module-owned classpath `.sql` resources and uses named bindings. Flyway migrations create source configuration plus Monitoring Profiles, ordered profile-to-source membership, profile criteria, and ordered typed keyword Analysis settings in the `configuration` schema.

The OpenAPI configuration contract is implemented under `/api/v1/sources` and `/api/v1/monitoring-profiles`. HTTP request records are separate from persistence types, Jakarta Validation enforces the input boundary, omitted `enabled` values default to `false`, expected not-found/invalid-configuration failures use centralized Micronaut `ExceptionHandler` beans, and the controller runs on `TaskExecutors.BLOCKING` for blocking database work.

Backend consumers read source and Monitoring Profile configuration through the narrow `SourceConfigurationProvider` and `MonitoringProfileConfigurationProvider` APIs rather than through persistence. Effective profiles include typed Analysis settings. Configuration CRUD operations remain internal application boundaries behind module-owned HTTP adapters. Omitted settings are only a compatibility bridge: create materializes deployment defaults, while replacement update preserves the profile's current effective settings.

## Operational and security notes

Persisting a URL is not outbound authorization. Configuration intentionally keeps URL syntax separate from live network authorization. Collection owns the accepted runtime destination policy; the source domain still does not perform DNS/network checks during CRUD so deterministic configuration behavior and legitimate operator-authorized internal sources remain possible.

## Read next

- [`contract.md`](contract.md) — authoritative module boundary and integration map
- [`../AGENTS.md`](../AGENTS.md) — shared module-development rules
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) — system architecture
- [`../../docs/specs/archive/subspecs/backend-configuration-persistence-rest.md`](../../docs/specs/archive/subspecs/backend-configuration-persistence-rest.md) — completed persistence/REST implementation history
