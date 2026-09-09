---
type: Test Guide
title: SignalHarvester testing modules
description: Entry point for reusable test support and cross-module backend integration tests.
---
# SignalHarvester testing modules

- `test-support/` — reusable deterministic test fixtures/helpers.
- `integration-tests/` — cross-module/backend integration and acceptance scenarios.

Read [`AGENTS.md`](AGENTS.md) and [`../docs/TESTS.md`](../docs/TESTS.md) before adding broad test infrastructure.

## Current state

The integration-test Gradle module is prepared with JUnit 5 and Testcontainers modules for PostgreSQL and Kafka. Container-backed scenarios will be added with the first persistence and Kafka adapters.
