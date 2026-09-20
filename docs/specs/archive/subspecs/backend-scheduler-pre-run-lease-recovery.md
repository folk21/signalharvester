---
type: Specification
title: Scheduler lease pre-run recovery
description: Bounded reliability fix so scheduler dispatch/setup failures release claimed due work without advancing its schedule.
document_role: subspec
spec_status: completed
parent: ../spec-signal-harvester-platform.md
---
# Scheduler lease pre-run recovery

## Status

Accepted on 2026-09-20 after the developer confirmed the focused Collection verification and canonical repository gate passed. The pre-run recovery semantics are now part of the accepted `COLLECTION.SCHEDULING` baseline; this specification is retained only as historical change context.

## Feature scope

- `COLLECTION.SCHEDULING` — cluster-safe interval scheduling and lease lifecycle.
- `RUNTIME.CONCURRENCY` — local executor lifecycle must not strand distributed schedule ownership before work starts.

## Goal

Do not strand due scheduled work until lease expiry when a replica claims the work but cannot start the collection run because local executor infrastructure is shutting down or rejecting setup.

## Current state

The scheduler claims due work transactionally before dispatching it to Micronaut's blocking executor. The claimed lease is renewed only after the worker starts and heartbeat scheduling succeeds.

Before this change, two pre-run failures left the lease persisted until its normal expiry:

1. the blocking executor rejected the claimed task;
2. the worker started, but heartbeat scheduling failed before `CollectionRunner.run(...)` began.

The normal lease expiry remained safe for duplicate prevention, but it introduced an avoidable interval where another healthy replica could not claim work that had never started.

## Requirements

### R1 — release only pre-run failures

If a claimed schedule cannot start because task dispatch or heartbeat setup fails before `CollectionRunner.run(...)` begins, the owning replica must make a best-effort release of that lease.

A failure after the collection run begins must keep the existing completion/lease-expiry semantics; this slice must not invent cancellation or transfer of in-flight work.

### R2 — preserve due time

Pre-run release must clear only lease ownership and lease expiry. It must not advance `next_due_at` because no collection run completed.

The released due work must therefore be immediately claimable by another replica, subject to normal token ownership checks.

### R3 — stale owners cannot release a successor lease

Release must be conditional on the exact lease token. If ownership has already moved to another claim, the old owner must not clear the successor's lease.

### R4 — expiry remains the fallback

Lease release is best effort. If PostgreSQL is unavailable while the replica is also failing to dispatch/setup the run, the existing lease expiry must remain the bounded recovery fallback.

## Non-goals

This slice does not:

- change interval calculation after a completed run;
- add cron/calendar scheduling or missed-run catch-up;
- cancel an already-started collection run when a heartbeat is later lost;
- add distributed handoff of in-flight collection work;
- change Collection Run, Kafka, REST, or OpenAPI contracts.

## Validation

Developer verification completed successfully on 2026-09-20. Acceptance covered:

- PostgreSQL integration coverage proving release leaves due work immediately claimable without advancing its due time;
- scheduler integration coverage for blocking-executor rejection before the run starts;
- scheduler integration coverage for heartbeat-scheduler failure before the run starts;
- token-ownership coverage proving a stale release cannot clear a successor lease;
- existing exclusive-claim, renewal, completion, and interval-reschedule tests remaining green;
- `./run_checks.sh` passing in the developer environment.
