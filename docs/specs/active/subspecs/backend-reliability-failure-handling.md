---
type: Specification
title: SignalHarvester backend asynchronous failure handling
description: Bounded Kafka consumer retries, poison-record dead-letter handling, terminal failure visibility, and offset-commit guarantees.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: verification-pending
---
# SignalHarvester backend asynchronous failure handling

## Status

Implementation complete. Developer `./run_checks.sh` verification pending.

## Goal

Prevent a permanently failing Kafka record from blocking Analysis, Results, or Event Observation indefinitely while preserving at-least-once safety when terminal failure handling itself is unavailable.

This slice implements the first bounded retry/DLQ increment required by umbrella R20 and strengthens the operational side of R21. Transactional outbox and stronger PostgreSQL/Kafka consistency remain a separate next stage under R22.

## Current state

Before this increment, the three implemented Kafka consumers disabled automatic offset commit and committed only after successful application processing. Their persistence paths were idempotent where required, but any malformed record or persistently failing application operation could remain uncommitted and repeatedly block its partition.

The implementation now adds the bounded retry and dead-letter behavior specified below; acceptance remains pending the developer verification gate.

## Requirements

### RF1 — deterministic invalid input is terminal

Malformed Protobuf, Kafka-key mismatches, and deterministic transport-to-module mapping failures must not be retried repeatedly. The consumer must publish one dead-letter record and may advance the source offset only after that DLQ publication is acknowledged.

### RF2 — application failures use bounded retry

Failures raised after a record has been decoded and validated are retryable for this first increment.

Each owning consumer must have configurable:

- maximum attempts including the initial attempt;
- fixed retry backoff;
- dead-letter topic.

The default maximum is three attempts with a 250 ms fixed backoff. Maximum attempts are capped at 10 and retry backoff is capped at 5 seconds so the listener cannot be configured for an unbounded or multi-minute sleep loop.

### RF3 — terminal failure contract is explicit

Dead-letter records must use the versioned `failure/v1/DeadLetterEvent` Protobuf contract.

The record must preserve enough information for diagnosis and controlled future replay:

- deterministic dead-letter identity derived from consumer group plus source Kafka position;
- logical consumer;
- effective consumer group;
- original topic, partition, offset, and key;
- original serialized payload;
- failure type and bounded message;
- attempts made;
- whether the terminal failure exhausted a retryable path.

The dead-letter identity must remain stable for the same consumer group and source Kafka position so duplicate terminal publication can be recognized by future inspection/replay tooling. This contract is diagnostic/recovery metadata. It is not a replacement for the original event schema.

### RF4 — source offsets follow terminal durability

A source offset may be committed only after either:

1. normal application processing completes successfully; or
2. the corresponding dead-letter publication is acknowledged.

If DLQ publication fails, the source offset must remain uncommitted so the failed record is not silently lost.

### RF5 — owning modules retain policy ownership

Analysis, Results, and Event Observation each own their retry configuration and dead-letter producer adapter. No new cross-module Java service or shared business module is introduced for this behavior.

The common Protobuf failure contract is shared only because the Kafka wire representation is intentionally common.

### RF6 — poison records must not block later partition work

Integration coverage must prove that a poison record can be dead-lettered and that a following valid record on the same source partition is still processed.

### RF7 — failure inspection is available from the DLQ contract

Operators must be able to inspect the dead-letter topic together with the checked-in `DeadLetterEvent` schema to recover original record identity/payload and the terminal failure reason.

A dedicated replay command/API and browser failure-management UI are intentionally deferred. Replay must be controlled rather than automatic because the underlying failure may still exist.

## Covered consumers

This increment applies to:

- Analysis consumption of `RawItemDiscovered`;
- Results consumption of `ItemAnalyzed` and `ItemRejected`;
- Event Observation consumption of raw/analyzed/rejected events.

Collection source-fetch retry behavior is not part of this Kafka-consumer slice. Collection already exposes bounded source failures as run outcomes and may receive a separate external-I/O retry policy only when source semantics justify it.

## Non-goals

This slice does not:

- implement transactional outbox or distributed exactly-once processing;
- automatically replay DLQ records;
- add a new functional reliability module;
- add a browser DLQ-management UI;
- retry deterministic invalid transport data;
- make unlimited or long blocking retries on Kafka listener threads.

## Compatibility

Existing business Kafka event schemas remain unchanged. `DeadLetterEvent` is a new additive event family.

The normal successful-path consumer semantics are unchanged. The behavioral change is limited to records that previously remained failing/uncommitted indefinitely: they now terminate through an acknowledged DLQ record after deterministic rejection or bounded retry exhaustion.

## Validation

Acceptance requires:

1. Protobuf serialization coverage for `DeadLetterEvent`;
2. Analysis unit coverage for retry recovery, immediate poison handling, exhausted retry, and DLQ-publication failure;
3. Results unit coverage for the same terminal offset guarantees across terminal Analysis events;
4. Event Observation unit coverage for retry and poison handling;
5. Kafka/PostgreSQL integration coverage proving a poison Results input is dead-lettered and a following record on the same partition is materialized;
6. existing idempotency/persistence integration tests remain green;
7. canonical `./run_checks.sh` passes in the developer environment.
