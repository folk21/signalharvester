---
type: Specification
title: Analysis outbox expired-lease fencing
description: Prevent an expired Analysis outbox owner from renewing stale lease ownership immediately before Kafka publication.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: verification-pending
---
# Analysis outbox expired-lease fencing

## Status

Implementation is complete and awaits developer verification.

The durable-ownership/crash-window review found that Analysis outbox pre-publication renewal is exact-token guarded but does not prove that the persisted lease is still live. If a dispatcher stalls beyond `lease-duration` and resumes before another replica claims the expired row, the old token can currently renew the already-expired lease and publish as if ownership had never lapsed.

Lease expiry is the recovery boundary. An expired former owner must not resurrect its lease merely because a successor has not claimed the row yet.

## Feature scope

This slice refines:

- `ANALYSIS.OUTBOX`;
- `RELIABILITY.IDEMPOTENCY`.

No new feature ID is introduced.

## Goal

Require pre-publication lease renewal to prove both exact-token ownership and a still-live persisted lease.

A dispatcher whose lease has already expired must skip publication and leave the row immediately reclaimable by another replica. Existing post-ack replay semantics remain intentionally at-least-once.

## Requirements

### R1 — expired lease cannot be renewed

`AnalysisOutboxStore.renewLease` must update a row only when:

- the event is still unpublished;
- the supplied lease token matches the persisted token;
- the persisted `lease_expires_at` is strictly later than the renewal time.

Token equality alone is insufficient after expiry.

### R2 — renewal uses one clock snapshot

The dispatcher must capture one current Analysis-clock instant for the renewal attempt and derive the new expiry from that same instant.

The persistence operation receives both the renewal time and the new expiry so the live-lease predicate and extension use the same application-owned time boundary.

### R3 — failed renewal prevents Kafka send

If renewal fails because the lease expired or ownership moved to another replica, the dispatcher must not send that outbox row to Kafka.

The row remains unpublished and recoverable through normal claim semantics.

### R4 — post-ack at-least-once semantics remain unchanged

This slice does not add a lease heartbeat around one Kafka send and does not change `markPublished` or `markFailed` ownership rules.

A Kafka acknowledgement followed by loss of the publication marker may still result in the same event being sent again with the same event id, topic, key, and payload.

Exact-token predicates continue to prevent a stale dispatcher from modifying a successor-owned row.

### R5 — no public contract or schema change

The slice must not change REST/OpenAPI, Protobuf, PostgreSQL schema, Kafka producer configuration, outbox batch size, lease duration, or public functional-module APIs.

## Failure semantics

```text
live exact-token lease
    -> renew from current clock instant
    -> Kafka send may begin

expired exact-token lease
    -> renewal updates zero rows
    -> no Kafka send
    -> row remains immediately reclaimable

successor has already reclaimed row
    -> old token renewal updates zero rows
    -> no Kafka send
    -> successor ownership remains unchanged
```

## Non-goals

This slice does not:

- provide distributed exactly-once publication;
- add continuous lease heartbeats during one Kafka send;
- change post-ack marker-failure replay behavior;
- change ordinary publication retry/backoff behavior;
- redesign the Analysis outbox store;
- introduce a shared lease framework.

## Validation

Acceptance requires:

1. PostgreSQL coverage proving an expired lease cannot be renewed with its still-persisted old token before a successor claim;
2. the same expired row remains claimable by a successor immediately afterward;
3. dispatcher coverage proving a later batch row whose original lease expires before pre-publication renewal is not sent by the stale dispatcher and can be published by a subsequent claim;
4. existing live renewal, stale-successor-token, ordinary retry, post-ack replay, and interruption tests remain green;
5. all relevant tests and validations pass.

## Implementation

The bounded implementation:

- passes the renewal instant explicitly through `AnalysisOutboxStore`;
- adds a live-lease predicate to `renew-lease.sql`;
- derives the replacement expiry from the same captured clock instant;
- adds focused PostgreSQL coverage for expired-owner renewal and reclaim;
- preserves existing Kafka publication and post-ack replay semantics.
