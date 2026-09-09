---
type: Development Guide
title: Application composition-root development rules
description: Rules for Micronaut startup, dependency wiring, global runtime configuration, and keeping business logic out of app.
---
# Application composition-root development rules

## Ownership

`app/` is the runnable Micronaut composition root. It owns startup, global framework/runtime configuration, module assembly, and deployment-facing application wiring.

## Boundaries

- Keep domain/business decisions in functional modules.
- Depend on module public APIs, not module internals.
- Do not turn `app` into a global service layer.
- Keep environment-specific values configurable.
- Global health/telemetry/bootstrap wiring may live here when no functional module owns it more naturally.

## Testing

Application-context tests should validate wiring without replacing module behavior tests. Cross-module behavioral scenarios belong in `testing/integration-tests`.
