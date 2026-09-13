---
type: Module Overview
title: SignalHarvester results module
description: Durable analyzed-result projection, terminal-analysis-event consumption, and public result queries.
---
# SignalHarvester results module

The authoritative ownership/integration boundary is [`contract.md`](contract.md).

## Current implementation

Results consumes version-one `ItemAnalyzed` and `ItemRejected` Kafka bytes and maps them immediately into Results-owned immutable models. `ResultProjectionService` owns write transactions; `JdbcResultProjectionRepository` writes only the module-owned `results` PostgreSQL schema.

Analyzed results are materialized by `(monitoringProfileId, normalizedItemId)`, so repeated Analysis publication updates one logical projection instead of creating duplicate result rows. Attributes and ordered tags are replaced in the same transaction as the main projection. Rejections are keyed by the upstream `sourceEventId`, preserving separate rediscoveries while making redelivery of one source event idempotent.

The Kafka listener disables automatic offset commit and commits only after durable projection returns successfully. This provides at-least-once retry safety, not distributed exactly-once processing.

`ResultQueryService` owns short read-only JDBC transactions over the same Results schema. The public REST adapter exposes:

- `GET /api/v1/results` — newest-first bounded result feed with optional monitoring-profile, source, information-category, relevance, classification, and analyzed-time filters;
- `GET /api/v1/results/{normalizedItemId}?monitoringProfileId=...` — detailed result content, attributes, ordered tags, and provenance.

The feed intentionally omits `normalizedContent` so a bounded list query does not return every potentially large payload; normalized attributes remain available for browsing. Detailed content is loaded only for a point lookup. Feed tag retrieval is performed in the same bounded SQL query rather than through per-result N+1 reads.

## Runtime configuration

The runnable application enables Results event consumption by default with:

```text
SIGNALHARVESTER_RESULTS_ENABLED=true
SIGNALHARVESTER_RESULTS_CONSUMER_GROUP=signalharvester-results-v1
```

The consumed topics are the existing `SIGNALHARVESTER_KAFKA_ITEM_ANALYZED_TOPIC` and `SIGNALHARVESTER_KAFKA_ITEM_REJECTED_TOPIC` settings. The read REST API is available independently of the consumer toggle when the application serves HTTP.

## Known limitations

- result browsing uses a bounded recent-result limit rather than cursor pagination;
- no full-text search;
- no Results SSE/live publication yet;
- rejected-result persistence remains internal operational state and is not exposed by this product API;
- no transactional outbox/exactly-once cross-resource guarantee;
- no bounded retry/DLQ policy yet, so a poison terminal event can repeatedly fail its partition.

## Read next

- [`contract.md`](contract.md)
- [`../AGENTS.md`](../AGENTS.md)
- [`../../docs/specs/archive/subspecs/backend-results-rest-api.md`](../../docs/specs/archive/subspecs/backend-results-rest-api.md)
- [`../../docs/IMPLEMENTATION.md`](../../docs/IMPLEMENTATION.md)
