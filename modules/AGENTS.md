---
type: Development Guide
title: Functional module development rules
description: Shared rules for ownership, public APIs, dependencies, persistence, adapters, testing, and documentation across functional modules.
---
# Functional module development rules

## Module ownership

Every directory directly under `modules/` is a cohesive functional module. It owns the behavior and infrastructure needed for that capability rather than contributing one technical layer to the whole repository.

A module may contain domain/model code, use cases, persistence, Kafka/HTTP adapters, scheduling, mapping, and tests when those concerns belong to the module.

## Public boundary

Expose the smallest practical Java API for synchronous cross-module collaboration. Keep implementation packages private by convention and enforce boundaries with Gradle/ArchUnit as the codebase grows.

A consumer module must not import another module's private persistence/adapters/implementations or query its tables directly.

Use Kafka/Protobuf when asynchronous integration is the intended system boundary. Do not serialize to Protobuf merely to call another module in the same JVM synchronously.

## Internal organization

Organize by cohesive behavior/sub-capability first. `api`, `application`, `model`, `persistence`, or `infrastructure` packages are allowed when useful, but no fixed package template is mandatory.

Prefer code that remains understandable if the module is later extracted, without prematurely designing every module as a remote service.

## Testing

Keep module behavior tests with the module. Use deterministic adapters/fakes for unit tests and shared Testcontainers-based integration tests where real Kafka/PostgreSQL behavior matters.

## Documentation

Each module README owns concise purpose/boundary/current-state information. Add a module `IMPLEMENTATION.md` only when concrete classes/call paths become substantial enough to justify a separate current-state document.
