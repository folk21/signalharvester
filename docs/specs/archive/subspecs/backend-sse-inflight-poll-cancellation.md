---
type: Specification
title: Backend SSE in-flight poll cancellation
description: Cancel active Results and Event Observation blocking poll work when the client cancels an SSE subscription.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: completed
---
# Backend SSE in-flight poll cancellation

## Status

Implementation is complete and accepted after the developer confirmed all relevant tests and validations passed.

The background-worker/executor lifecycle review found that Results and Event Observation SSE subscriptions cancel future scheduled polling but do not interrupt a blocking poll that is already executing on Micronaut's blocking executor.

## Feature scope

This slice refines:

- `RESULTS.LIVE`;
- `DIAGNOSTICS.EVENT_OBSERVATION`.

No new feature ID is introduced.

## Goal

Make client cancellation release both future scheduled SSE polling and the currently executing blocking poll task on a best-effort interruption boundary.

A disconnected client must not leave avoidable Results or Event Observation polling work running until the underlying query returns naturally.

## Current state

Both SSE implementations:

- remain reactive `Publisher` boundaries at the HTTP layer;
- execute Jdbi-backed polling on Micronaut's shared blocking executor;
- use the scheduled executor only to delay the next bounded poll;
- cancel a pending `ScheduledFuture` when the client cancels the subscription;
- stop emitting after the subscription's cancellation flag is set.

The active blocking-executor task is currently submitted with `execute(...)` and no cancellable task handle is retained. Cancellation therefore cannot interrupt a poll that is already blocked in `currentCursor()` or `pollAfter(...)`.

## Requirements

### R1 — retain a cancellable active-poll handle

Each SSE subscription must retain the `Future<?>` for its current blocking-executor poll task.

The shared Micronaut executor remains externally owned; the SSE implementation must not create or shut down a replacement executor.

### R2 — client cancellation interrupts active polling

When the Reactive Streams subscription is cancelled, the implementation must:

1. mark the subscription cancelled;
2. cancel any pending scheduled next poll;
3. cancel the active blocking poll with interruption requested.

Cancellation is best effort. The stream does not claim that every JDBC/driver operation is synchronously abortable.

### R3 — submission/cancellation races remain safe

If cancellation races with blocking-task submission, the newly submitted task must still be cancelled once its `Future` becomes available.

No new polling task may be intentionally scheduled after cancellation.

### R4 — cancellation is not an SSE failure

A query that observes the cancellation interrupt may throw while unwinding. Because the subscription is already cancelled, that failure must not be delivered to the client as `onError` and must not produce later SSE events.

### R5 — existing stream semantics remain unchanged

This slice does not change:

- Results or Event Observation cursor persistence;
- `Last-Event-ID` semantics;
- ready/result/event/keepalive framing;
- downstream-demand accounting;
- polling interval, batch size, or reconnect configuration;
- REST/OpenAPI contracts;
- database queries or schemas;
- executor ownership.

## Non-goals

This slice does not:

- add JDBC statement-specific cancellation;
- create dedicated executors per stream or module;
- change Micronaut HTTP streaming primitives;
- add timeout policy beyond existing infrastructure;
- redesign SSE polling as push messaging;
- change Results search/pagination behavior.

## Validation

Acceptance requires:

1. a focused Results test that blocks inside `pollAfter(...)`, cancels the subscription, and observes worker interruption;
2. equivalent Event Observation coverage;
3. cancellation-induced query failure is not delivered as `onError` after the client cancellation boundary;
4. existing Results and Event Observation SSE controller tests remain green;
5. all relevant tests and validations pass.

## Implementation

The bounded implementation:

- submits polling through `ExecutorService.submit(...)` instead of fire-and-forget `execute(...)`;
- stores the returned `Future<?>` per subscription;
- calls `cancel(true)` on the active poll during subscription cancellation;
- performs a post-submission cancellation check to close the race where the client disconnects before the returned future is stored;
- adds focused deterministic cancellation tests in Results and Event Observation.
