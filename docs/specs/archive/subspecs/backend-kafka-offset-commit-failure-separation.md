---
type: Specification
title: Kafka offset commit failure separation
description: Keep Analysis, Results, and Event Observation application retry/DLQ semantics inside the listener while delegating successful per-record offset commit to Micronaut Kafka.
document_role: subspec
spec_status: completed
parent: ../spec-signal-harvester-platform.md
---
# Kafka offset commit failure separation

## Status

Completed and accepted on 2026-09-20 after the corrected Micronaut `SYNC_PER_RECORD` implementation passed focused listener verification, `HttpPipelineSmokeIntegrationTest`, and the canonical repository gate.

The first implementation moved manual `commitSync()` outside the application retry `try/catch`, but still let a manual commit failure escape from an `OffsetStrategy.DISABLED` listener. Cross-module integration then stalled while the Kafka listener/runtime was handling that escaped post-processing failure. The accepted implementation removes manual commit ownership from the listener methods and uses Micronaut `SYNC_PER_RECORD` commit semantics instead.

## Feature scope

- `RELIABILITY.KAFKA_RETRY` — keep bounded retry scoped to decoded application processing failures.
- `RELIABILITY.DEAD_LETTER` — commit terminal poison/exhausted records only after acknowledged owner-specific DLQ publication.
- `RELIABILITY.IDEMPOTENCY` — preserve safe redelivery when a framework-level offset commit does not become durable.

The change applies to the Analysis, Results, and Event Observation Kafka consumers.

## Goal

Separate application processing from Kafka offset lifecycle at the listener boundary.

The listener must own decoding, bounded application retry, and terminal DLQ publication. Once the listener completes normally, Micronaut Kafka owns the synchronous per-record offset commit. A Kafka commit failure must therefore not re-enter SignalHarvester application retry or DLQ classification.

## Requirements

### KOC1 — application retry ends at durable processing success

Once decoded application processing returns successfully, that listener invocation must not call the processor/projector/recorder again because of later offset-commit behavior.

### KOC2 — successful listener completion delegates commit to Micronaut

Analysis, Results, and Event Observation listeners must use `OffsetStrategy.SYNC_PER_RECORD` and must not call `Consumer.commitSync()` directly.

A listener may return normally only after either:

1. durable application processing succeeds; or
2. deterministic/terminal failure is successfully published to the owning DLQ.

Micronaut then performs the synchronous offset commit for that completed record.

### KOC3 — escaped listener failure must remain uncommitted

If application processing ultimately cannot complete because terminal DLQ publication also fails, the listener must throw. Under the configured per-record strategy the record must not be treated as successfully completed by SignalHarvester.

### KOC4 — deterministic invalid input remains terminal

Decode, key-validation, and deterministic mapping failures continue to bypass application retry. They publish one owner-specific DLQ record and return normally only after that publication succeeds, allowing the framework to commit the source record afterward.

### KOC5 — application retry/DLQ policy remains module-owned

Do not replace the existing Analysis, Results, or Event Observation bounded processing retry policies with Micronaut Kafka listener retry policies. The framework owns successful per-record offset commit only; SignalHarvester modules retain processing retry and DLQ classification.

## Non-goals

This stage does not:

- add a custom in-listener offset-commit retry loop;
- add a second framework-level retry policy around successful application processing;
- introduce Kafka transactions or exactly-once processing;
- suppress valid at-least-once redelivery when an offset commit does not become durable;
- change dead-letter event schemas or operator replay semantics.

## Validation

Accepted on 2026-09-20 after the developer confirmed:

1. Analysis unit coverage passed for `SYNC_PER_RECORD`, bounded processing retry, terminal DLQ behavior, and propagation when DLQ publication fails;
2. equivalent Results unit coverage passed;
3. equivalent Event Observation unit coverage passed;
4. the real `HttpPipelineSmokeIntegrationTest` completed and tore down normally through Kafka -> Analysis -> Results/Event Observation;
5. existing poison-record, DLQ-recovery, PostgreSQL/Kafka integration, and cross-module tests remained green;
6. `./run_checks.sh` passed in the developer environment.
