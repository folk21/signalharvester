---
type: Specification
title: Controlled dead-letter recovery
description: Add ADMIN-only owner-specific inspection and deliberate replay for Analysis, Results, and Event Observation dead-letter records.
document_role: subspec
spec_status: verification-pending
parent: ../spec-signal-harvester-platform.md
---
# Controlled dead-letter recovery

## Status

Current backend implementation focus. The bounded Kafka retry/DLQ baseline remains unchanged; this slice adds explicit operator recovery over real dead-letter records without turning the backend into a general Kafka injection surface.

## Feature scope

- `RELIABILITY.DEAD_LETTER` — controlled inspection and recovery of terminal consumer failures.
- `RELIABILITY.IDEMPOTENCY` — safe repeated recovery through the existing owner-specific application paths.
- `CONTRACTS.HTTP` — ADMIN-only recovery endpoints.
- `SECURITY.AUTHORIZATION` — backend enforcement for operator recovery.

## Goal

Allow an administrator to inspect one known DLQ position and deliberately retry its original record after the underlying problem is corrected.

Recovery must preserve the original source key/payload and consumer ownership while avoiding side effects in unrelated consumer groups.

## Design boundary

Analysis, Results, and Event Observation share the versioned `DeadLetterEvent` transport schema but retain independent failure-policy ownership.

A naive replay that republishes the stored payload to the original shared topic is unsafe for owner-specific recovery. For example, replaying an Event Observation DLQ record onto `RawItemDiscovered` would also redeliver it to Analysis and could create a new duplicate terminal event merely to repair a diagnostic projection.

Therefore this slice uses owner-specific local replay:

1. an ADMIN identifies the owning DLQ partition and offset;
2. the owning module reads that exact `DeadLetterEvent` from its configured DLQ;
3. the module validates dead-letter identity, logical consumer, configured consumer group, and allowed source topic;
4. replay requires the exact `deadLetterId` returned by inspection as an explicit confirmation;
5. the original source key/payload is decoded by the same module-owned decoder used by the live Kafka listener;
6. the resulting application model is passed to the same owner-specific processing/projection/recording boundary;
7. the shared source topic is not republished and no source consumer offset is rewritten.

## Requirements

### R1 — read a real DLQ record

Recovery must address a concrete record by the owning dead-letter topic partition and offset. The API must not accept arbitrary serialized event payloads or arbitrary source topics from the caller.

If the requested DLQ position is unavailable, recovery returns not found rather than synthesizing a record.

### R2 — validate ownership and identity

Before inspection/replay the backend must validate:

- `DeadLetterEvent.dead_letter_id` equals the canonical `consumerGroup:sourceTopic:sourcePartition:sourceOffset` identity;
- the Kafka DLQ key equals `dead_letter_id`;
- the logical consumer matches the owning module;
- the stored consumer group matches the module's currently configured consumer group;
- the source topic belongs to that module's supported input topics;
- source partition/offset and attempts are valid.

Invalid records must not be replayed.

### R3 — explicit confirmation

Replay must require `expectedDeadLetterId` and compare it to the inspected record at the requested DLQ position. A mismatch fails without processing the source payload.

This prevents an operator from replaying a different record if the requested Kafka position or supplied identifier is stale or mistaken.

### R4 — preserve original source semantics

Replay must use the exact stored source key and serialized payload from `DeadLetterEvent`.

The same transport decoder/key validation used by normal live consumption must be reused. Recovery must not introduce a second decoder with weaker validation.

Event Observation must also preserve the original source topic, partition, and offset in its diagnostic projection.

### R5 — owner-local replay

Recovery must call the owning module's existing application boundary directly:

- Analysis → `RawItemProcessor`;
- Results → `AnalysisOutcomeProjector`;
- Event Observation → `EventObservationRecorder`.

The implementation must not republish the source record to the shared Kafka topic and must not modify consumer-group source offsets.

Repeated replay relies on the existing owner-specific idempotency semantics. It does not claim cross-resource exactly-once behavior.

### R6 — bounded operator work

Each owner must bound concurrent inspection/replay operations. The Kafka read timeout must be configurable and bounded. Recovery calls run on Micronaut's blocking executor rather than a Netty event-loop thread.

### R7 — administrative HTTP boundary

Provide ADMIN-only GET inspection and POST replay endpoints for each owner under `/api/v1/admin/.../dead-letters/{partition}/{offset}`.

The inspection response may expose operational metadata and failure diagnostics, but must not return the serialized source payload. It reports only its byte size.

POST remains subject to the existing cookie/CSRF policy. Expected recovery failures use stable HTTP statuses.

### R8 — no automatic replay

This capability is deliberately operator-driven. It must not scan DLQs, automatically retry records, continuously poll dead-letter topics, or add a replay scheduler.

## Non-goals

- generic Kafka produce/injection endpoints;
- automatic or bulk DLQ replay;
- mutation/deletion of DLQ history;
- rewinding Kafka consumer-group offsets;
- changing `DeadLetterEvent` schema;
- replay UI in the companion frontend;
- replacing bounded normal consumer retry;
- distributed exactly-once guarantees.

## Validation

The stage is ready for acceptance when automated verification proves:

1. each module can inspect a real record from its configured DLQ;
2. replay requires exact dead-letter confirmation;
3. records from the wrong consumer/group/topic are rejected;
4. original source key/payload is decoded through the normal owner decoder;
5. Results and Event Observation replay remain idempotent under repeated invocation;
6. owner-local recovery does not append a new record to the shared source topic;
7. invalid positions/records and Kafka availability failures map predictably at HTTP boundaries;
8. recovery controllers execute blocking work off the Netty event loop;
9. inspection responses do not expose source payload bytes;
10. `./run_checks.sh` passes.

## Implementation tasks

1. Extract reusable module-local Kafka record decoders from the three live listeners.
2. Add owner-specific bounded DLQ readers/recovery services.
3. Add ADMIN inspection/replay REST and OpenAPI contracts.
4. Add deterministic controller and real Kafka/PostgreSQL recovery coverage.
5. Update owning reliability/configuration/usage/testing documentation.
6. Run the canonical repository gate and archive this specification after developer acceptance.
