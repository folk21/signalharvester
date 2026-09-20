---
type: Specification
title: Analysis outbox pre-publication lease renewal
description: Reliability hardening so batch queueing time cannot expire an Analysis outbox row lease before its Kafka publication starts.
document_role: subspec
spec_status: completed
parent: ../spec-signal-harvester-platform.md
---
# Analysis outbox pre-publication lease renewal

## Status

Completed and accepted on 2026-09-20 after focused Analysis verification and the canonical repository gate passed.

## Feature scope

- `ANALYSIS.OUTBOX` — preserve exclusive lease ownership for each claimed row until its Kafka publication begins.
- `RELIABILITY.IDEMPOTENCY` — retain stable at-least-once replay identity when publication outcome remains uncertain.

## Goal

Prevent batch queueing time from consuming a later Analysis outbox row's entire lease before that row reaches Kafka publication.

The existing dispatcher claims a bounded batch in one short PostgreSQL transaction and then sends each entry sequentially. A lease starts when the whole batch is claimed. With a sufficiently large batch or elevated Kafka acknowledgement latency, a later entry can therefore reach its send only after its original lease has expired. Another replica may then reclaim the same pending row while the original replica is about to publish it.

The outbox remains intentionally at-least-once. This change narrows avoidable concurrent publication caused by local batch queueing; it does not claim distributed exactly-once behavior or guarantee that a single Kafka send can never outlive a configured lease.

## Requirements

### OLR1 — renew immediately before publication

Before sending each claimed outbox entry to Kafka, the dispatcher must extend that entry's existing lease in a short application-owned PostgreSQL transaction.

The renewed expiry must be calculated from the current Analysis clock, not from the original batch-claim time.

### OLR2 — ownership remains exact-token guarded

Lease renewal must update exactly one unpublished row owned by the supplied lease token.

If another replica has already reclaimed the row and replaced the token, the stale dispatcher must not publish that entry. It must leave recovery to the current owner instead of overwriting or releasing the successor lease.

### OLR3 — Kafka remains outside database transactions

Lease renewal must complete before the Kafka send begins. No JDBC transaction or PostgreSQL row lock may remain open while waiting for broker acknowledgement.

The existing success and retry-marker transactions remain separate short transactions after the send.

### OLR4 — batch and retry semantics remain compatible

The existing bounded claim batch, publication-attempt counting, retry backoff, stable event identity, topic/key/payload bytes, and post-ack replay semantics must remain unchanged.

This change must not convert the dispatcher into a generic exactly-once or distributed-lock framework.

### OLR5 — verification protects lease aging and stale ownership

PostgreSQL integration coverage must prove that:

- a later entry in a sequentially processed batch receives a fresh lease before its send, so another replica cannot reclaim it merely because earlier sends consumed most of the original batch lease;
- a stale lease token cannot renew a row after a successor replica has reclaimed it;
- the current owner can renew the row successfully.

## Failure semantics

If pre-publication lease renewal fails, the dispatcher must not send the entry to Kafka because it cannot prove current ownership. The row remains recoverable through its current lease state or normal lease expiry.

A Kafka acknowledgement followed by failure to persist `published_at` remains an accepted at-least-once replay window and continues to reuse the same event id, topic, key, and payload.

## Non-goals

This stage does not:

- add a background lease heartbeat around one Kafka send;
- change Kafka producer acknowledgement or timeout configuration;
- introduce Kafka transactions or exactly-once delivery;
- change the outbox schema or event contracts;
- change source-offset commit semantics;
- introduce a shared outbox framework.

## Validation

Accepted on 2026-09-20 after the developer confirmed:

1. focused Analysis unit/integration tests passed;
2. PostgreSQL coverage proved pre-publication renewal and stale-token safety;
3. existing outbox success, retry, post-ack replay, and claim-expiry scenarios remained green;
4. `./run_checks.sh` passed.
