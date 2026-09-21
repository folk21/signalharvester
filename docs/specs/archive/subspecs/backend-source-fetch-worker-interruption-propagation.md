---
type: Specification
title: Source fetch worker interruption propagation
description: Propagate lifecycle interruption from Collection source-fetch worker virtual threads to run-level coordination so scheduled recovery does not treat shutdown cancellation as an ordinary failed run.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: completed
---
# Source fetch worker interruption propagation

## Status

Completed and accepted on 2026-09-21 after the developer confirmed all relevant tests and validations passed.

The background-worker/executor lifecycle review found a cancellation gap inside `SourceFetchCoordinator`. The coordinator itself can remain non-interrupted while executor shutdown interrupts one of its source-fetch worker virtual threads. If that worker fails with wrapped interruption, the coordinator currently rethrows it as an ordinary unexpected worker failure. A scheduled Collection Run can therefore reach `MonitoringProfileScheduler` without an interrupt signal and be finalized as an ordinary failed run, advancing `next_due_at` during shutdown.

## Feature scope

This slice refines:

- `COLLECTION.RUNS`;
- `COLLECTION.SCHEDULING`;
- `RUNTIME.CONCURRENCY`.

No new feature ID is introduced.

## Goal

Preserve lifecycle cancellation across the source-fetch worker boundary.

If a worker virtual thread is interrupted while a Collection Run is coordinating source fetches, the coordinator must convert that worker-local cancellation into coordinator-thread interruption, cancel peer work best effort, and allow the existing run/scheduler interruption recovery semantics to remain authoritative.

## Requirements

### R1 — worker interruption is not an ordinary worker failure

A source-fetch task that exits with its worker interrupt flag set or with an `InterruptedException` in its runtime failure cause chain must be marked as lifecycle interruption before its `Future` completes exceptionally.

Ordinary unexpected runtime failures without interruption keep the existing propagation behavior.

### R2 — coordinator interruption is restored

When `SourceFetchCoordinator` observes an interrupted-worker failure through `ExecutionException`, it must:

- cancel remaining in-flight fetches best effort;
- set the coordinator thread interrupt flag;
- propagate run-level interruption instead of an ordinary worker failure.

This allows `CollectionRunService` and `MonitoringProfileScheduler` to reuse their accepted interruption/recovery behavior.

### R3 — ordinary source failure isolation is unchanged

`SourceFetchException` remains an ordinary per-source terminal outcome and must not cancel unrelated source work unless it also represents lifecycle interruption through the worker interrupt flag or an interruption cause.

### R4 — executor ownership is unchanged

`SourceFetchCoordinator` continues to use the injected Micronaut blocking executor and must not own or shut down it.

No new executor, shutdown hook, timeout, or distributed cancellation protocol is introduced.

### R5 — no public contract change

The slice must not change REST/OpenAPI, Protobuf, database schema, Collection concurrency configuration, scheduling intervals, or public module APIs.

## Failure semantics

```text
ordinary SourceFetchException
    -> per-source failure outcome
    -> peer work continues

ordinary unexpected worker failure
    -> cancel peer work
    -> propagate failure

worker lifecycle interruption
    -> mark interrupted worker completion
    -> coordinator observes interrupted completion
    -> cancel peer work
    -> restore coordinator interrupt flag
    -> propagate run-level cancellation
    -> scheduled run does not advance next_due_at
```

## Non-goals

This slice does not:

- redesign `SourceFetchCoordinator` concurrency or backpressure;
- add structured-concurrency APIs;
- add a new shared interruption utility;
- change ordinary executor-rejection handling;
- release already-started schedule leases immediately;
- change source-level best-effort semantics for non-interruption failures.

## Validation

Acceptance requires:

1. unit coverage proving worker-thread interruption reaches the coordinator as interruption even when the coordinator thread was not initially interrupted;
2. the coordinator interrupt flag is restored before propagation;
3. in-flight peer fetch work is cancelled best effort;
4. ordinary unexpected worker failure behavior remains unchanged;
5. existing partial executor-rejection and source-failure isolation coverage remains green;
6. all relevant tests and validations pass.

## Implementation

The bounded implementation:

- marks runtime failures from an interrupted source-fetch worker before they cross the `Future` boundary;
- recognizes that marker from `ExecutionException`;
- restores coordinator-thread interruption before propagation;
- reuses the existing cancellation of peer `Future` instances;
- adds focused virtual-thread regression coverage.
