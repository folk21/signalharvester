---
type: Module Overview
title: SignalHarvester results module
description: Durable analyzed-result projection and terminal-analysis-event consumption.
---
# SignalHarvester results module

The authoritative ownership/integration boundary is [`contract.md`](contract.md).

## Current implementation

Results consumes version-one `ItemAnalyzed` and `ItemRejected` Kafka bytes and maps them immediately into Results-owned immutable models. `ResultProjectionService` owns the JDBC transaction; `JdbcResultProjectionRepository` writes only the module-owned `results` PostgreSQL schema.

Analyzed results are materialized by `(monitoringProfileId, normalizedItemId)`, so repeated Analysis publication updates one logical projection instead of creating duplicate result rows. Attributes and ordered tags are replaced in the same transaction as the main projection. Rejections are keyed by the upstream `sourceEventId`, preserving separate rediscoveries while making redelivery of one source event idempotent.

The Kafka listener disables automatic offset commit and commits only after durable projection returns successfully. This provides at-least-once retry safety, not distributed exactly-once processing.

## Runtime configuration

The runnable application enables Results by default with:

```text
SIGNALHARVESTER_RESULTS_ENABLED=true
SIGNALHARVESTER_RESULTS_CONSUMER_GROUP=signalharvester-results-v1
```

The consumed topics are the existing `SIGNALHARVESTER_KAFKA_ITEM_ANALYZED_TOPIC` and `SIGNALHARVESTER_KAFKA_ITEM_REJECTED_TOPIC` settings.

## Known limitations

- no result query REST API yet;
- no pagination/filtering/detail queries yet;
- no Results SSE publication yet;
- no transactional outbox/exactly-once cross-resource guarantee;
- no bounded retry/DLQ policy yet, so a poison terminal event can repeatedly fail its partition;
- rejection persistence is internal state and is not yet exposed as a product API.

## Read next

- [`contract.md`](contract.md)
- [`../AGENTS.md`](../AGENTS.md)
- [`../../docs/specs/active/subspecs/backend-results-persistence.md`](../../docs/specs/active/subspecs/backend-results-persistence.md)
- [`../../docs/IMPLEMENTATION.md`](../../docs/IMPLEMENTATION.md)
