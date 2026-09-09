---
type: Development Guide
title: Common module development rules
description: Rules preventing the shared common module from becoming a business-code dumping ground.
---
# Common module development rules

## Ownership

`common/` contains only small, stable, genuinely generic primitives/utilities needed by multiple independently owned modules.

## Admission rule

Before moving code here, verify that:

- the concept is not owned by one functional module;
- reuse is real rather than speculative;
- semantics are stable across consumers;
- sharing will reduce duplication without coupling unrelated module evolution.

Business entities, repository interfaces, Kafka payload models, feature configuration, and module-specific exceptions normally do not belong here.
