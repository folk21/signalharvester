---
type: Development Guide
title: Testing development rules
description: Rules for deterministic test support, Testcontainers integration, network isolation, and cross-module acceptance tests.
---
# Testing development rules

## Boundaries

- `test-support` contains reusable deterministic fixtures/helpers only when multiple owners genuinely need them.
- `integration-tests` owns cross-module/backend scenarios.
- Module-specific behavior tests stay with the owning module.

## Determinism

Default tests must not depend on public websites, external SaaS, production credentials, wall-clock timing races, or uncontrolled external data.

Use deterministic fake HTTP sources and controllable time/ID sources where behavior depends on them.

## Real infrastructure

Use Testcontainers for PostgreSQL and Kafka when persistence/serialization/transaction/consumer behavior matters. Do not mock away the integration behavior a test is intended to validate.

Place container-backed and cross-module integration tests under the owning project's `src/integrationTest/java` source set. Keep `src/test/java` for fast tests that are safe to execute in the default `test` lifecycle. Do not rely on JUnit tags as the primary lifecycle boundary.

Follow the repository-wide test readability rules for linked class-level Javadocs, concise Javadoc-style test-purpose comments, semantic fixture constants, parameterized data sets, and DRY helpers. Keep shared `test-support` utilities semantic and stable rather than centralizing coincidentally similar setup from unrelated modules.
