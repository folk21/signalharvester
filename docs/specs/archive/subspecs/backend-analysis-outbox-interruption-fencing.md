---
type: Specification
title: Analysis outbox interruption fencing
description: Keep Analysis outbox lifecycle interruption outside normal publication failure classification so cancelled dispatch stops the current batch and leaves leased rows recoverable.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: completed
---
# Analysis outbox interruption fencing

## Status

Accepted on 2026-09-21 after the developer confirmed all relevant tests and validations passed.

Implementation and verification are complete.

The background-worker lifecycle review found a cancellation gap in `AnalysisOutboxDispatcher`. Analysis uses a synchronous Micronaut `@KafkaClient` method, so a dispatcher thread may block while waiting for Kafka publication acknowledgement. If lifecycle interruption causes that send or an adjacent persistence operation to throw, the existing generic `RuntimeException` handling treats the interruption as an ordinary publication failure, records retry metadata, and can continue publishing later rows from the same claimed batch.

Lifecycle cancellation is not an application publication failure. Once interruption is observed, the dispatcher must stop the current run and leave unfinished leased rows to the existing lease-expiry recovery path.

## Feature scope

- `ANALYSIS.OUTBOX` — preserve bounded lease-driven publication while respecting worker cancellation.
- `RELIABILITY.IDEMPOTENCY` — retain normal at-least-once recovery after an interrupted publication attempt.

## Goal

Prevent Analysis outbox dispatch from converting worker interruption into retry state or continuing the current claimed batch after cancellation.

Normal broker/database failures remain retryable through the existing `markFailed` path. Interruption instead escapes the scheduled invocation with the interrupt flag preserved; unfinished rows remain unpublished and recover through their current lease state or lease expiry.

## Requirements

### R1 — interruption escapes publication failure classification

If the dispatcher observes an interrupted thread, or a caught runtime failure contains an `InterruptedException` cause, it must propagate lifecycle interruption instead of classifying the operation as an ordinary outbox publication failure.

When interruption is discovered through a wrapped cause, the dispatcher must restore the current thread interrupt flag before propagating.

### R2 — interrupted publication does not write retry metadata

If interruption occurs while waiting for Kafka acknowledgement, renewing the lease, or recording publication state, the dispatcher must not intentionally convert that cancellation into `markFailed` retry metadata.

The row remains recoverable through the existing lease and at-least-once semantics.

### R3 — interruption stops the current claimed batch

Before starting each claimed entry, the dispatcher must honor an already-set interrupt flag.

After interruption escapes one entry, later entries from that dispatcher invocation must not be published.

### R4 — ordinary failure behavior remains unchanged

Non-interruption Kafka or persistence failures continue to use the existing bounded retry-marker path:

- unsuccessful publication remains unpublished;
- retry metadata is recorded when ownership still permits it;
- the dispatcher may continue with later entries in the bounded batch.

### R5 — existing ownership and replay semantics remain unchanged

This slice does not change:

- claim SQL or outbox schema;
- exact-token lease renewal;
- pre-publication renewal timing;
- stable event id/topic/key/payload;
- post-ack replay semantics;
- Kafka producer acknowledgement configuration;
- scheduler/executor ownership.

No exactly-once guarantee is introduced.

## Non-goals

This slice does not:

- introduce a custom executor or shutdown hook;
- cancel an in-flight Kafka request through a separate API;
- change Kafka producer timeouts;
- change outbox lease duration or batch size;
- change listener retry/DLQ behavior;
- change public APIs, REST/SSE contracts, Protobuf contracts, or persistence schemas.

## Validation

Acceptance requires:

1. PostgreSQL-backed Analysis coverage proving a wrapped interruption from synchronous Kafka publication escapes the dispatcher;
2. the interrupt flag is preserved/restored;
3. the interrupted row is not deliberately marked with ordinary publication-failure metadata;
4. later rows from the same claimed batch are not sent;
5. existing outbox success, ordinary failure/retry, lease renewal, stale ownership, and post-ack replay tests remain green;
6. all relevant tests and validations pass.

## Implementation

The bounded implementation:

- checks the interrupt flag before dispatching each claimed row;
- distinguishes lifecycle interruption from ordinary runtime failures in publication and lease-update catch paths;
- recognizes wrapped `InterruptedException` causes and restores the interrupt flag;
- propagates interruption before `markFailed` classification;
- adds PostgreSQL integration coverage with two pending outbox rows to prove the interrupted first send stops the batch.
