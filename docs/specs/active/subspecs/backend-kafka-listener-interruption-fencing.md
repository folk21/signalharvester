---
type: Specification
title: Kafka listener interruption fencing
description: Keep listener-thread interruption outside Analysis, Results, and Event Observation retry/DLQ classification so shutdown or cancellation cannot become a terminal business failure.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: verification-pending
---

# Kafka listener interruption fencing

## Status

Implementation complete; verification pending.

The systematic Kafka consumer lifecycle review found that an application attempt which throws while the listener thread is already interrupted can currently enter ordinary retry exhaustion and terminal DLQ handling. This is especially visible on the final configured attempt or when retry backoff is zero.

## Feature scope

- `RELIABILITY.KAFKA_RETRY` — keep lifecycle cancellation outside application retry.
- `RELIABILITY.DEAD_LETTER` — do not emit a terminal business DLQ record solely because processing was interrupted by listener lifecycle cancellation.
- `RELIABILITY.IDEMPOTENCY` — preserve normal at-least-once redelivery after escaped interruption.

The change applies to Analysis, Results, and Event Observation Kafka consumers.

## Goal

Treat listener-thread interruption as lifecycle cancellation rather than an application failure classification.

An interrupted invocation must escape the listener before retry exhaustion or terminal DLQ publication can turn the interrupted work into successful listener completion. Micronaut/Kafka may then stop, rebalance, or redeliver according to the normal consumer lifecycle.

## Current state

All three listeners already propagate `InterruptedException` raised by a non-zero retry sleep and restore the thread interrupt flag.

A narrower gap remains when the processing attempt itself throws `RuntimeException` while the listener thread's interrupt flag is set:

- on the final configured attempt the listener can publish a terminal DLQ record and return normally;
- with zero retry backoff the listener can continue retrying without ever entering `Thread.sleep`;
- the resulting successful listener completion makes the source record eligible for framework offset commit even though the processing failure occurred under lifecycle interruption.

That conflates cancellation/shutdown semantics with the module-owned business retry/DLQ policy.

## Requirements

### R1 — interruption precedes retry/DLQ classification

After a decoded application attempt throws, Analysis, Results, and Event Observation must check the current listener thread's interrupt status before deciding to retry or publish a terminal DLQ record.

If the thread is interrupted, the listener must throw instead of continuing application failure classification.

### R2 — interrupt status remains preserved

The listener must not clear the thread interrupt flag when propagating interrupted processing.

The same preservation rule continues to apply when retry sleep itself is interrupted.

### R3 — zero-backoff retry cannot bypass cancellation

An interrupt observed before the next retry must escape even when configured retry backoff is zero.

Zero backoff must not create a busy retry loop on an interrupted listener thread.

### R4 — interrupted work is not terminally dead-lettered

When interruption fencing triggers:

- no owner-specific `DeadLetterEvent` is published for that invocation;
- the listener does not return normally;
- source-offset completion remains outside SignalHarvester application code and the source record stays eligible for normal at-least-once redelivery.

This requirement does not retract an already acknowledged DLQ publication if interruption happens after terminal failure handling has actually completed.

### R5 — ordinary retry/DLQ behavior remains unchanged

When the listener thread is not interrupted:

- deterministic decode/key/mapping failures remain terminal without retry;
- application failures still use the configured bounded retry policy;
- retry exhaustion still publishes the owner-specific DLQ record;
- failed DLQ publication still escapes;
- successful listener completion still delegates `SYNC_PER_RECORD` offset commit to Micronaut.

### R6 — no new consumer framework policy

This slice must not add framework-level retry, manual `Consumer.commitSync()`, Kafka transactions, custom rebalance handling, or a second consumer execution model.

## Non-goals

This slice does not:

- guarantee that arbitrary blocking JDBC or Kafka operations respond immediately to thread interruption;
- redefine Kafka `max.poll.interval.ms`;
- prevent normal duplicate delivery after a rebalance or failed framework commit;
- change retry count/backoff configuration;
- change dead-letter schemas or operator replay behavior;
- change module APIs, REST/SSE contracts, or persistence models.

## Validation

Acceptance requires:

1. Analysis unit coverage proving an interrupted processing failure escapes without DLQ publication and preserves interrupt status;
2. equivalent Results projection coverage;
3. equivalent Event Observation recording coverage;
4. existing retry recovery, exhaustion, poison-record, DLQ-publication failure, and `SYNC_PER_RECORD` tests remain green;
5. all relevant tests and validations pass.

## Implementation

The bounded implementation:

- fences application failure classification on the listener thread's current interrupt flag;
- checks interruption before zero-backoff retry can continue;
- preserves existing sleep-interruption behavior;
- adds focused unit coverage in all three owning modules.

No OpenAPI, Protobuf, migration, configuration, or public Java API change is required.
