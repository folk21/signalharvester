---
type: Specification
title: Backend shared demand-driven polling lifecycle refactoring
description: Extract stable demand, delayed scheduling, and cancellation lifecycle duplicated by Results and Event Observation SSE polling into the constrained common shared kernel.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: verification-pending
---
# Backend shared demand-driven polling lifecycle refactoring

## Status

Implementation is complete and awaits developer verification.

After SSE in-flight poll cancellation was accepted, a focused duplication review confirmed that Results and Event Observation independently implement the same demand accounting, blocking-task submission, delayed rescheduling, cancellation, and failure-termination lifecycle.

## Feature scope

This slice refines:

- `PLATFORM.MODULAR_MONOLITH`;
- `RESULTS.LIVE`;
- `DIAGNOSTICS.EVENT_OBSERVATION`.

No new feature ID is introduced.

## Goal

Remove stable concurrency-lifecycle duplication without moving Results or Event Observation business/SSE semantics into the shared kernel.

The reusable primitive must remain framework-neutral and genuinely generic so `common` does not become an owner of HTTP, Micronaut, cursor, persistence, or module-specific behavior.

## Requirements

### R1 — shared code is limited to generic lifecycle

`common` may own demand accounting, blocking-task submission, delayed rescheduling, scheduled-task cancellation, active-task interruption, and terminal failure/cancellation state.

It must not own:

- Micronaut `Event` or `TaskScheduler` types;
- Results or Event Observation DTOs/criteria;
- cursor semantics;
- ready/result/event/keepalive framing;
- query/persistence contracts;
- module configuration prefixes.

### R2 — no new dependency direction

Results and Event Observation already depend on `common`; the refactoring must not add a new functional-module dependency or a new external library dependency to `common`.

The scheduling boundary is supplied to the common primitive through a small JDK-only callback.

### R3 — preserve observable SSE behavior

The refactoring must preserve:

- downstream demand semantics;
- ready/result/event/keepalive behavior;
- durable cursor/resume semantics;
- delayed polling while outstanding demand remains;
- cancellation of pending delayed polling;
- best-effort interruption of the active blocking poll;
- suppression of cancellation-induced unwind failure after cancellation;
- existing REST/OpenAPI contracts and configuration.

### R4 — shared lifecycle has direct tests

The common primitive must have focused tests for:

- consuming exactly requested items;
- cancelling delayed polling;
- interrupting an active blocking poll and suppressing its unwind failure after cancellation.

Existing Results and Event Observation SSE cancellation tests remain module-level regression coverage.

### R5 — avoid speculative deduplication

Other superficially similar classes are not moved into `common` unless they satisfy the same admission rule: genuinely generic semantics, real reuse, stable behavior, and no module ownership leakage.

## Selective duplication review

The focused review also found repeated shapes across Analysis, Results, and Event Observation around:

- dead-letter recovery exceptions and HTTP response/controller adapters;
- Kafka reliability configuration interfaces;
- dead-letter publishers;
- Kafka listener retry/error structure.

These remain module-owned for now. Their similarity reflects repeated capability-local boundaries and configuration rather than a proven stable shared-kernel abstraction. Moving them now would couple independently owned modules and would be a broader refactor than this slice requires.

Configuration `MonitoringProfileAnalysisSettings` and Analysis `KeywordAnalysisSettings` are also intentionally separate because they sit on different ownership/transport boundaries despite similar fields.

## Implementation

The implementation adds `common.concurrent.DemandDrivenPollingLoop<T>` using only JDK concurrency/types.

Results and Event Observation retain their local Reactive Streams `Subscription`, cursor state, pending SSE events, query calls, and event mapping. Their subscriptions delegate only demand/scheduling/cancellation mechanics to the shared loop.

## Validation

Acceptance requires:

1. focused `common` tests for demand, delayed cancellation, and active-poll interruption;
2. existing Results SSE tests remain green;
3. existing Event Observation SSE tests remain green;
4. no new external dependency is added to `common`;
5. all relevant tests and validations pass.
