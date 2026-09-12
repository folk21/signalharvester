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

The integration-test Gradle module contains cross-module PostgreSQL/Kafka scenarios for persisted enabled-source collection and the collection-to-analysis event flow. Module-local Testcontainers coverage remains with the owning configuration, collection, and analysis modules.

Integration-tagged tests are intentionally excluded from the default `test` lifecycle and run through the dedicated `integrationTest` task. See [`../docs/TESTS.md`](../docs/TESTS.md) for the authoritative command matrix.
