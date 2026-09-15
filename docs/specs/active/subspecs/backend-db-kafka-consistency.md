---
type: Specification
title: SignalHarvester backend database and Kafka consistency
description: Transactional Analysis outbox with lease-based Kafka delivery and stable event identity across publication retries.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: verification-pending
---
# SignalHarvester backend database and Kafka consistency

## Status

Implementation complete. Developer `./run_checks.sh` verification pending.

## Goal

Close the implemented Analysis failure window between authoritative PostgreSQL deduplication state and terminal Kafka publication without introducing distributed transactions.

This slice implements the transactional-outbox part of umbrella R22 for the current backend path that must atomically persist authoritative state and arrange publication of a corresponding integration event.

## Current state

Analysis previously updated `analysis.normalized_item_claims` and synchronously published `ItemAnalyzed` or `ItemRejected` inside one application-owned JDBC transaction. A Kafka acknowledgement followed by PostgreSQL commit failure could therefore publish an event whose corresponding deduplication transaction rolled back. Redelivery could publish another terminal event.

Collection run history remains operational best-effort history rather than authoritative event state. Results and Event Observation consume Kafka and persist projections but do not publish corresponding business integration events. They therefore do not require another outbox in this increment.

The implementation now stages exact serialized terminal Analysis events in PostgreSQL together with the deduplication transaction and publishes them asynchronously through a lease-based dispatcher.

## Requirements

### OC1 — terminal event staging is transactional

For every accepted or duplicate Analysis outcome, the same PostgreSQL transaction must contain both:

- the authoritative deduplication claim/update; and
- one serialized outbox record representing the terminal `ItemAnalyzed` or `ItemRejected` event.

Failure to append the outbox record must roll back the deduplication change.

### OC2 — outbox stores final transport identity

The outbox must store the final Kafka topic, key, serialized Protobuf bytes, stable event id, and creation time before the database transaction commits.

The dispatcher must publish those stored bytes directly. It must not remap the domain outcome and generate a new event id on each attempt.

### OC3 — Kafka publication occurs outside the database transaction

The dispatcher must claim pending rows in a short PostgreSQL transaction, publish Kafka records after that transaction completes, and then persist publication outcome in another short transaction.

No JDBC transaction or row lock may be held while waiting for Kafka acknowledgement.

### OC4 — multi-replica dispatch uses leases

Outbox claims must use bounded leases and PostgreSQL row-level coordination so multiple backend replicas do not normally publish the same pending row concurrently.

A crashed dispatcher must not permanently strand a row. After its lease expires another replica may reclaim it.

### OC5 — post-ack failure remains replay-safe

A crash or database failure after Kafka acknowledgement but before `published_at` is recorded may cause the same outbox row to be published again.

The repeated publication must use the same event id, topic, key, and payload. Downstream Results and Event Observation consumers must continue relying on their existing idempotent event handling.

This is at-least-once outbox delivery, not distributed exactly-once messaging.

### OC6 — failed publication remains pending

A Kafka send failure must leave the outbox row unpublished and eligible for a later bounded-delay retry. Failure metadata and publication-attempt count must remain inspectable in PostgreSQL.

### OC7 — input offset semantics move to database durability

The Analysis raw-item Kafka listener may commit the consumed source offset after `RawItemProcessingService` successfully commits its PostgreSQL transaction containing both deduplication state and the outbox record.

It must no longer wait for terminal Analysis Kafka publication on the request path.

### OC8 — configuration is bounded and explicit

The dispatcher must expose configuration for:

- enabled state;
- poll interval;
- batch size;
- lease duration;
- retry backoff.

Batch size and duration bounds must prevent accidental unbounded claims or effectively permanent leases.

## Non-goals

This slice does not:

- add a generic shared outbox framework or new functional module;
- introduce Kafka transactions or two-phase commit;
- change the existing `ItemAnalyzed` / `ItemRejected` Protobuf contracts;
- add an outbox UI;
- automatically replay DLQ records;
- make collection run history and raw-item publication one distributed transaction;
- claim exactly-once Kafka delivery.

## Compatibility

The terminal Analysis event schemas, topics, and keys remain unchanged. Consumers continue to receive the same Protobuf contracts.

The timing changes: terminal Analysis events may appear shortly after the source raw-item offset has been committed because Kafka publication is now asynchronous from the committed PostgreSQL outbox.

## Validation

Acceptance requires:

1. unit coverage proving analyzed/rejected outcomes are serialized once into final outbox records;
2. PostgreSQL integration coverage proving deduplication state and the outbox record commit together and an outbox append failure rolls back the Analysis transaction;
3. dispatcher coverage for successful publication, Kafka failure retry state, and stable stored bytes/event identity;
4. existing Results/Event Observation idempotency coverage remains green under repeated terminal event delivery;
5. existing reliability/DLQ tests remain green with the new Analysis processing boundary;
6. canonical `./run_checks.sh` passes in the developer environment.
