---
type: Specification
title: Analysis outbox failure-state lease fencing
description: Prevent an expired Analysis outbox owner from writing retry metadata after publication failure and delaying immediate recovery.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: verification-pending
---
# Analysis outbox failure-state lease fencing

## Status

Implementation is complete and awaits developer verification.

The durable-ownership/crash-window review found a second expiry gap in the Analysis outbox lifecycle. Pre-publication renewal now requires a still-live exact-token lease, but an unsuccessful Kafka send can itself outlive that renewed lease. If the send then fails before any successor claim, the former owner can still execute `markFailed` using only the old token and replace the already-expired recovery boundary with a future retry timestamp.

Lease expiry must remain a real ownership boundary. Once the lease has expired, the former owner must not delay recovery by writing retry metadata merely because its old token is still persisted.

## Feature scope

This slice refines:

- `ANALYSIS.OUTBOX`;
- `RELIABILITY.IDEMPOTENCY`.

No new feature ID is introduced.

## Goal

Require ordinary publication-failure metadata to be written only while the dispatcher still owns a live exact-token lease.

If Kafka publication fails after lease expiry, the stale dispatcher must leave the row unchanged so another replica can reclaim it immediately through the existing claim path.

## Requirements

### R1 — failure metadata requires a live lease

`AnalysisOutboxStore.markFailed` must update a row only when:

- the event is still unpublished;
- the supplied lease token matches the persisted token;
- the persisted `lease_expires_at` is strictly later than the publication-failure time.

Token equality alone is insufficient after expiry.

### R2 — failure time and retry time share one clock snapshot

The dispatcher must capture one Analysis-clock instant when ordinary Kafka publication failure is observed.

That instant is the ownership-fencing time and the configured retry backoff is added to the same instant to derive `nextAttemptAt`.

### R3 — expired failed send remains immediately reclaimable

If Kafka publication fails after the renewed lease has expired, stale `markFailed` must update zero rows.

The dispatcher may log the persistence rejection, but it must not manufacture a new future retry boundary. The row remains unpublished with its expired lease and is immediately eligible for a successor claim.

### R4 — live ordinary failures keep existing retry behavior

If publication fails while the exact-token lease is still live, existing behavior remains unchanged:

- bounded failure metadata is recorded;
- the lease token is cleared;
- `lease_expires_at` becomes the configured `nextAttemptAt`;
- the row becomes claimable after the normal retry backoff.

### R5 — post-ack marker semantics remain unchanged

This slice does not add an expiry predicate to `markPublished`.

Kafka acknowledgement is an external side effect that may legitimately outlive the renewed lease. After acknowledgement, exact-token mutation continues to protect successor-owned state, while marker failure may still cause deliberate at-least-once replay of the same stable event id/topic/key/payload.

### R6 — no public contract or schema change

The slice must not change REST/OpenAPI, Protobuf, PostgreSQL schema, Kafka producer configuration, retry durations, lease durations, or public functional-module APIs.

## Failure semantics

```text
Kafka send fails while lease is still live
    -> markFailed succeeds
    -> retry metadata is recorded
    -> row waits for configured backoff

Kafka send fails after lease expiry
    -> markFailed updates zero rows
    -> stale owner cannot extend recovery state
    -> row stays immediately reclaimable

successor already owns the row
    -> old-token markFailed updates zero rows
    -> successor state remains unchanged
```

## Non-goals

This slice does not:

- provide distributed exactly-once publication;
- add a lease heartbeat around one Kafka send;
- change post-ack `markPublished` behavior;
- change ordinary retry/backoff configuration;
- redesign the Analysis outbox store;
- introduce a generic distributed-lease framework.

## Validation

Acceptance requires:

1. PostgreSQL coverage proving an expired owner cannot write failure metadata before any successor claim;
2. the same row remains immediately claimable by a successor after that rejected stale mutation;
3. dispatcher coverage proving a Kafka failure that outlives the renewed lease does not apply retry backoff and the row can be reclaimed immediately;
4. existing live publication-failure retry, pre-publication renewal, stale-token, post-ack replay, and interruption tests remain green;
5. all relevant tests and validations pass.

## Implementation

The bounded implementation:

- passes an explicit failure timestamp through `AnalysisOutboxStore.markFailed`;
- adds a still-live lease predicate to `mark-failed.sql`;
- derives `nextAttemptAt` from the same captured failure instant;
- adds focused PostgreSQL coverage for expired-owner failure metadata and immediate reclaim;
- preserves existing live-failure retry and post-ack at-least-once semantics.
