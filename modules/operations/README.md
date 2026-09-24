---
type: Module Guide
title: Operations module
description: Current implementation of the operational change journal and persisted health-report foundation.
---
# Operations module

See [`contract.md`](contract.md) for authoritative ownership and dependency rules.

The module currently provides the first `backend-observability-intelligence` implementation slice:

- durable sanitized operational change records;
- transactional journaling hooks used by Source, Monitoring Profile, and security-administration mutations;
- audit-safe successful DLQ replay markers plus typed deployment/test-scenario markers;
- persisted versioned Health Snapshots with count-bounded retention;
- bounded latest Health Report JSON/Markdown views;
- nearest before/after snapshot lookup around one recorded change.

The current foundation snapshot intentionally reports `UNKNOWN` with policy version `foundation-v1`. It captures available in-process low-cardinality capacity signals and recent change references, but it does not claim system health before the deterministic/statistical Health Engine is implemented.

## Read next

- [`contract.md`](contract.md)
- [`../../docs/specs/active/subspecs/backend-observability-intelligence.md`](../../docs/specs/active/subspecs/backend-observability-intelligence.md)
- [`../../docs/IMPLEMENTATION.md`](../../docs/IMPLEMENTATION.md)
