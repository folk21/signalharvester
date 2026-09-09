---
type: Configuration Guide
title: Configuration
description: Ownership and intended semantics of runtime environment configuration and persisted SignalHarvester application configuration.
---
# Configuration

## Ownership

SignalHarvester has two distinct configuration categories:

1. **deployment/runtime configuration** — ports, database/Kafka endpoints, credentials, telemetry endpoints, and similar environment-specific settings;
2. **application configuration** — monitoring profiles, external sources, schedules, filters, extraction settings, and analysis settings persisted by the configuration module.

Do not mix these categories or hardcode values that belong to either one.

## Runtime configuration

Concrete Micronaut configuration files and environment-variable contracts will be documented here once runtime wiring is implemented. Secrets must not be committed.

## Persisted application configuration

The configuration module will own application-level configuration and its PostgreSQL migrations. Other modules must consume it through an explicit module API or event flow rather than reading configuration tables directly.

## Compatibility

Configuration fields that affect persisted behavior, API contracts, or source interpretation require explicit compatibility consideration and tests when implemented.
