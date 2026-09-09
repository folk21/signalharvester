---
type: Module Overview
title: SignalHarvester analysis module
description: Normalization, deduplication, relevance, classification, and scoring ownership.
---
# SignalHarvester analysis module

## Ownership

Own analysis of discovered information, including normalization/deduplication boundaries and replaceable relevance/classification/scoring logic.

## Boundary

Do not couple core analysis to one external AI provider. Generated Protobuf messages are transport types, not the module domain model.

## Current state

The Gradle/source skeleton exists; concrete implementation is not yet established.

## Read next

- [`../AGENTS.md`](../AGENTS.md)
- [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md)
- [`../../docs/specs/active/subspecs/backend-project-structure.md`](../../docs/specs/active/subspecs/backend-project-structure.md)
