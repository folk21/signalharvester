---
type: Specification
title: Kafka listener failed-poll rewind
description: Rewind every partition from a failed Kafka poll when Analysis, Results, or Event Observation listener work escapes so Micronaut poll skipping cannot turn an application failure into silent record loss.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: completed
---

# Kafka listener failed-poll rewind

## Status

Completed and accepted on 2026-09-20 after the developer confirmed all relevant tests and validations passed.

The continuing Kafka consumer lifecycle review found a framework-boundary gap after the accepted interruption fencing. Analysis, Results, and Event Observation deliberately throw when processing cannot complete, for example when terminal DLQ publication fails or listener lifecycle interruption escapes application classification. The listeners use Micronaut `SYNC_PER_RECORD` but do not configure a framework retry policy.

Under the current Micronaut Kafka listener error semantics, the default `NONE` error strategy stops processing the remaining records from the current poll after an escaped listener exception. Without an explicit rewind, later consumption can continue from the consumer's advanced poll position and a later successful per-record commit can make records that were never invoked by SignalHarvester unrecoverable through the normal committed-offset path.

## Feature scope

- `RELIABILITY.KAFKA_RETRY` — preserve module-owned bounded application retry without adding a second framework retry policy.
- `RELIABILITY.DEAD_LETTER` — keep failed DLQ publication outside successful listener completion without losing other records from the same poll.
- `RELIABILITY.IDEMPOTENCY` — deliberately allow duplicate processing when a failed poll is rewound, relying on the existing consumer idempotency boundaries.

The change applies to Analysis, Results, and Event Observation Kafka consumers.

## Goal

Preserve at-least-once recovery for every record returned by a Kafka poll when one SignalHarvester listener invocation escapes.

The framework may stop processing the rest of that poll, but before normal polling can continue the consumer position for every partition represented by the failed poll must be reset to the first offset returned for that partition. Reprocessing records that completed earlier in the same poll is acceptable and must remain safe through the existing module idempotency guarantees.

## Requirements

### R1 — escaped listener failure rewinds the current poll

Analysis, Results, and Event Observation listeners must provide per-listener exception handling for failures that escape their listener methods.

The handler must use the consumer and complete `ConsumerRecords` batch supplied by Micronaut for the failed poll and rewind before the framework continues normal consumption.

### R2 — every partition in the failed poll is rewound

For each `TopicPartition` represented by the failed poll, the handler must seek the consumer to the first offset returned for that partition in that poll.

Rewinding only the record or partition that raised the exception is insufficient because the framework may stop invoking records from other partitions that were returned by the same poll.

### R3 — missing poll context fails closed

If the listener exception does not expose the complete failed poll, or exposes an empty poll, the handler must fail rather than silently resume consumption under incomplete rewind semantics.

The committed consumer-group offsets remain the durable recovery boundary after consumer restart.

### R4 — no framework processing retry is introduced

The accepted module-owned application retry/DLQ policy remains unchanged.

This slice must not add Micronaut `RETRY_ON_ERROR`, manual `Consumer.commitSync()`, a second processing retry loop, Kafka transactions, or custom rebalance ownership.

### R5 — duplicate processing from rewind remains safe

A rewind may cause records that completed earlier in the same poll to be invoked again before or after their existing committed offset becomes visible to the current consumer session.

The existing idempotency boundaries remain authoritative:

- Analysis deduplicates normalized work per Monitoring Profile and persists terminal publication through its outbox;
- Results projects terminal Analysis events by stable logical/event identities;
- Event Observation records by unique event identity.

This slice does not claim exactly-once processing.

### R6 — successful and terminally handled records are unchanged

When listener work completes normally:

- successful application processing still returns normally;
- deterministic invalid input and exhausted application failures still return normally only after acknowledged owner-specific DLQ publication;
- Micronaut `SYNC_PER_RECORD` still owns the source-offset commit;
- the failed-poll rewind handler is not involved.

### R7 — interruption fencing remains authoritative

The accepted interruption-fencing behavior remains unchanged. Listener-thread interruption escapes before application retry exhaustion or terminal DLQ classification, preserving the interrupt flag.

The escaped exception is then subject to the same failed-poll rewind boundary as other listener failures.

## Non-goals

This slice does not:

- tune `max.poll.interval.ms`, `max.poll.records`, heartbeat, or session timeout configuration;
- introduce a framework-level retry strategy;
- add manual source-offset commits;
- prevent harmless duplicate processing caused by at-least-once rewind;
- change dead-letter schemas or controlled replay semantics;
- change public Java APIs, REST/SSE contracts, Protobuf contracts, persistence schemas, or module ownership;
- provide custom Kafka rebalance callbacks.

## Validation

Acceptance requires:

1. Analysis unit coverage proving that failed-poll rewind seeks every represented partition to its first polled offset;
2. equivalent Results coverage;
3. equivalent Event Observation coverage;
4. all three listener classes are wired as per-listener Micronaut Kafka exception handlers;
5. existing `SYNC_PER_RECORD`, retry, terminal DLQ, DLQ-publication failure, interruption-fencing, poison-record, and idempotency tests remain green;
6. all relevant tests and validations pass.

## Implementation

The bounded implementation:

- implements `KafkaListenerExceptionHandler` directly on each of the three listener beans;
- obtains the complete failed poll from `KafkaListenerException`;
- seeks every partition in that poll to the first offset returned for that partition;
- fails closed if complete poll context is unavailable;
- adds focused unit coverage using Kafka `MockConsumer` in each owning module.

No OpenAPI, Protobuf, migration, configuration, public Java API, or framework retry-policy change is required.
