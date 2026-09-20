---
type: Specification
title: Scheduler expired-lease fencing
description: Prevent an expired Collection scheduler lease from being renewed or completed by its former owner after ownership has lapsed.
document_role: subspec
spec_status: verification-pending
parent: ../spec-signal-harvester-platform.md
---

# Scheduler expired-lease fencing

## Status

Implementation complete; verification pending.

The systematic lifecycle/reliability review identified a durable-ownership gap in the accepted `COLLECTION.SCHEDULING` lease lifecycle. Focused verification and the canonical repository gate are still required before acceptance.

## Feature scope

- `COLLECTION.SCHEDULING` — cluster-safe scheduled Collection work and lease lifecycle.

## Goal

Ensure lease expiry is a real fencing boundary.

Once a Collection scheduler lease reaches its persisted expiry, the former owner must not be able to renew that lease or mark the scheduled work complete merely because no successor has claimed the row yet.

Another replica must remain able to reclaim the overdue work after expiry.

## Current state

A due Monitoring Profile is claimed with an exact lease token and `lease_until`.

Successor claims replace the token, so token checks already prevent an older owner from mutating a lease after ownership has moved to another replica.

The lifecycle review found a narrower gap when the lease has expired but no successor has claimed it yet:

- `renew.sql` checks only the exact token and can extend an already-expired lease;
- `complete.sql` checks only the exact token and can advance `next_due_at` after that lease has expired.

This means a stalled replica can resume after the configured lease duration and regain effective ownership before another replica claims the overdue row. It can also complete expired work and suppress the overdue reclaim path.

That contradicts the accepted scheduler invariant that a crashed or stalled replica eventually loses its lease after the configured lease duration so another replica can claim overdue work.

## Requirements

### R1 — renewal requires a live lease

Scheduler renewal must succeed only when:

- the Monitoring Profile row still carries the exact lease token; and
- the persisted lease expiry is strictly later than the renewal timestamp.

If the lease has already expired, renewal must return `false`.

An expired owner must not resurrect the lease even when no successor has claimed the row yet.

### R2 — completion requires a live lease

Scheduler completion must succeed only when:

- the Monitoring Profile row still carries the exact lease token; and
- the persisted lease expiry is strictly later than the completion timestamp.

If the lease has already expired, completion must return `false`.

Rejected stale completion must not advance `next_due_at` or otherwise consume the overdue schedule.

### R3 — expired work remains reclaimable

The existing claim rule remains authoritative:

- a row whose `lease_until` is null or at/before the claim timestamp is claimable when it is due;
- claim atomically replaces the lease token and expiry.

After a stale renewal or completion is rejected because of expiry, another replica must be able to claim the due work through the existing path.

### R4 — exact-token successor fencing remains unchanged

A former owner must still be unable to renew, release, or complete a successor lease because every lease mutation remains conditional on the exact token.

This slice must not weaken existing successor-token protection.

### R5 — release semantics remain unchanged

Pre-run release continues to be conditional on the exact token and continues not to advance `next_due_at`.

An expired release does not need a new expiry predicate in this slice because clearing an already-expired token cannot consume scheduled work, and an already-claimed successor remains protected by the token predicate.

### R6 — running work is not cancelled or handed off

This slice does not cancel a Collection Run when heartbeat renewal fails or the lease expires.

The existing behavior remains:

- heartbeat loss is logged;
- the already-started run may finish locally;
- completion after lease expiry is rejected;
- another replica may reclaim the overdue schedule after expiry.

Downstream duplicate-safety and existing Collection/Analysis idempotency remain responsible for normal at-least-once recovery when ownership is lost after work has started.

## Non-goals

This slice does not:

- change the scheduler interval calculation;
- change pre-run lease release behavior;
- add distributed cancellation or handoff for in-flight Collection Runs;
- add cron/calendar scheduling;
- change Collection Run, REST, OpenAPI, Kafka, or Protobuf contracts;
- change scheduler configuration keys;
- redesign the scheduler persistence model.

## Validation

Acceptance requires:

1. PostgreSQL integration coverage proving an expired lease cannot be renewed before a successor claim;
2. PostgreSQL integration coverage proving an expired lease cannot be completed before a successor claim;
3. both rejected operations leave the due row reclaimable by another replica;
4. existing before-expiry renewal and completion coverage remains green;
5. existing stale-token successor protection remains green;
6. all relevant tests and validations pass.

## Implementation

The bounded implementation:

- adds an expiry predicate to scheduler lease renewal;
- adds an expiry predicate to scheduler completion;
- reuses the operation timestamp already bound by the Jdbi adapter;
- adds focused PostgreSQL integration coverage for both stale-owner paths.

No Java API, REST, event-contract, migration, or configuration change is required.
