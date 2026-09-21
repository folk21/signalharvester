---
type: Specification
title: Collection Run interruption recovery
description: Keep Collection lifecycle interruption outside per-source publication failure classification and prevent interrupted scheduled runs from advancing their next-due time.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: verification-pending
---
# Collection Run interruption recovery

## Status

Implementation is complete and awaits developer verification.

The remaining background-worker/executor lifecycle review found a cancellation gap across Collection Run publication and scheduler completion. A lifecycle interrupt during acknowledged Kafka publication can be wrapped as `RawItemPublicationException` and classified as an ordinary per-source publication failure. A scheduled run that exits under lifecycle interruption is then still finalized through `ProfileScheduleCoordinator.complete(...)`, advancing `next_due_at` even though the local worker was cancelled.

## Feature scope

This slice refines:

- `COLLECTION.RUNS`;
- `COLLECTION.SCHEDULING`;
- `RUNTIME.CONCURRENCY`;
- `RELIABILITY.IDEMPOTENCY`.

No new feature ID is introduced.

## Goal

Treat Collection lifecycle interruption as run-level cancellation rather than an ordinary source outcome.

If an already-started scheduled Collection Run is interrupted, the worker must stop local work, preserve interruption, cancel its heartbeat, and leave the current schedule lease to the existing expiry/reclaim path instead of advancing the next due time.

## Requirements

### R1 — interrupted Kafka publication aborts the Collection Run

If `RawItemPublicationException` represents lifecycle interruption through the current thread interrupt flag or an interruption cause, `CollectionRunService` must propagate a run-level failure rather than create `PUBLICATION_FAILED` and continue later source/item publication.

When interruption is discovered through a wrapped cause, the current thread interrupt flag must be restored before propagation.

Ordinary non-interruption Kafka publication failure remains a best-effort per-source terminal outcome.

### R2 — interruption stops peer source work

The propagated run-level interruption must pass through the existing `SourceFetchCoordinator` handler-failure path so remaining in-flight fetch tasks are cancelled best effort and replacement work is not intentionally continued.

This slice does not redesign source-level failure isolation for ordinary fetch/publication failures.

### R3 — interrupted scheduled work does not advance `next_due_at`

If `CollectionRunner.run(...)` exits under lifecycle interruption, `MonitoringProfileScheduler` must:

- preserve/restore the worker interrupt flag;
- cancel the scheduled heartbeat;
- not call normal schedule completion for that interrupted run;
- leave the current exact-token lease in persisted state until normal expiry or successor recovery.

The interrupted run must therefore remain overdue and reclaimable once the lease expires.

### R4 — do not immediately release an already-started lease

An already-started interrupted run must not use the pre-run release path.

Immediate release after partial external side effects could cause unnecessary concurrent retry while the cancelled worker is still unwinding. The accepted lease-expiry boundary remains the bounded recovery mechanism for in-flight work.

### R5 — ordinary run failures keep current scheduling semantics

A non-interruption `RuntimeException` from an already-started Collection Run keeps the existing scheduler behavior: log the failed run and complete the owned live schedule lease through the normal completion path.

This slice changes only lifecycle cancellation semantics.

### R6 — no transport or persistence contract change

The slice must not change REST/OpenAPI, Protobuf, database schema, schedule interval calculation, source concurrency configuration, or Kafka retry configuration.

## Failure semantics

The intended boundaries are:

```text
ordinary source/Kafka failure
    -> per-source terminal outcome
    -> run may continue

lifecycle interruption
    -> run-level cancellation
    -> cancel peer fetch work best effort
    -> scheduled worker cancels heartbeat
    -> do not advance next_due_at
    -> existing lease expires
    -> overdue schedule becomes reclaimable
```

Any already acknowledged raw events remain subject to the existing deterministic raw-item identity and downstream at-least-once/idempotent processing model.

## Non-goals

This slice does not:

- introduce distributed cancellation or in-flight handoff between replicas;
- release an already-started schedule lease immediately;
- add Collection retry/DLQ policy;
- redesign `SourceFetchCoordinator` concurrency;
- change ordinary per-source best-effort failure semantics;
- change scheduling intervals or missed-run catch-up semantics.

## Validation

Acceptance requires:

1. Collection Run unit coverage proving wrapped publication interruption aborts the run, preserves interruption, and stops later publication work;
2. PostgreSQL-backed scheduler coverage proving an interrupted started run does not advance the schedule while its lease is live;
3. the same overdue schedule becomes reclaimable at lease expiry;
4. ordinary Kafka publication failures remain isolated per source;
5. existing pre-run release, lease renewal/completion fencing, and source-fetch cancellation coverage remain green;
6. all relevant tests and validations pass.

## Implementation

The bounded implementation:

- fences `RawItemPublicationException` interruption before `PUBLICATION_FAILED` classification;
- restores the current thread interrupt flag from wrapped interruption causes;
- makes `MonitoringProfileScheduler` skip normal completion after interrupted run execution;
- retains lease expiry rather than immediate release as the recovery path;
- adds focused Collection unit and PostgreSQL integration coverage.
