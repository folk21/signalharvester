---
type: Module Overview
title: SignalHarvester results module
description: Durable analyzed-result projection, terminal-analysis-event consumption, and public REST/SSE reads.
---
# SignalHarvester results module

The authoritative ownership/integration boundary is [`contract.md`](contract.md).

## Current implementation

Results consumes version-one `ItemAnalyzed` and `ItemRejected` Kafka bytes and maps them immediately into Results-owned immutable models. `ResultProjectionService` owns write transactions; `JdbiResultProjectionRepository` writes only the module-owned `results` PostgreSQL schema through named SQL resources.

Analyzed results are materialized by `(monitoringProfileId, normalizedItemId)`, so repeated Analysis publication updates one logical projection instead of creating duplicate result rows. Attributes and ordered tags are replaced in the same transaction as the main projection. Rejections are keyed by the upstream `sourceEventId`, preserving separate rediscoveries while making redelivery of one source event idempotent.

The Kafka listener uses Micronaut `SYNC_PER_RECORD`. Deterministic transport/key/mapping failures are dead-lettered immediately, while projection failures retry within the configured bound. The listener treats listener-thread interruption as lifecycle cancellation: interrupted projection escapes before retry exhaustion or DLQ classification, preserves the interrupt flag, and remains eligible for normal redelivery. The listener returns normally only after durable projection or acknowledged Results DLQ publication; Micronaut synchronously commits that completed record afterward. DLQ publication failure escapes before successful completion, and a later framework commit failure remains outside Results application retry/DLQ classification and may cause normal at-least-once redelivery. This provides bounded at-least-once recovery behavior, not distributed exactly-once processing.

Controlled recovery reads one known Results DLQ partition/offset, validates the DLQ key/id, logical consumer, configured consumer group, and allowed Analysis source topic, then requires explicit dead-letter-id confirmation. Replay uses the same `AnalysisOutcomeKafkaRecordDecoder` and `AnalysisOutcomeProjector`; it never republishes the shared Analysis topic or changes consumer offsets. Existing projection idempotency absorbs repeated operator replay.

`ResultQueryService` owns short read-only application transactions over the same Results schema. `JdbiResultQueryRepository` loads static SQL from module-owned resources and uses StringTemplate 4 only to include active browsing/live predicates before named-value binding. The public REST adapter exposes:

- `GET /api/v1/results` — newest-first result feed with optional monitoring-profile, source, information-category, relevance, classification, analyzed-time, and text-search filters; the JSON body remains `ResultSummary[]`, while `X-Next-Cursor` carries opaque keyset continuation when another page exists;
- `GET /api/v1/results/{normalizedItemId}?monitoringProfileId=...` — detailed result content, attributes, ordered tags, and provenance.

REST pagination uses the deterministic order `analyzedAt DESC`, `monitoringProfileId ASC`, `normalizedItemId ASC`. Cursors are URL-safe, versioned, and bound to the filters/search expression that produced them; clients may change only `limit` between pages. Search uses PostgreSQL `websearch_to_tsquery` with the `simple` configuration over title and normalized content. `V14` adds the browse-order and GIN search indexes. The endpoint remains a current-projection view, so concurrent Result updates may move a logical item relative to a previously issued page cursor.

The feed intentionally omits `normalizedContent` so a bounded list query does not return every potentially large payload; normalized attributes remain available for browsing. Detailed content is loaded only for a point lookup. Feed tag retrieval is performed in the same bounded SQL query rather than through per-result N+1 reads.

`GET /api/v1/results/stream` exposes resumable Server-Sent Events. `results.live_result_cursors` stores one current monotonic cursor per logical analyzed result. Cursor advancement happens in the same transaction as projection updates and does not advance when the same `analysisEventId` is redelivered. Fresh connections receive `ready`, then later `result` events; idle connections receive `keepalive`. Browser `Last-Event-ID` resumes durable polling after the last received cursor. The stream represents current projections rather than an append-only event history, so several updates to one logical result while a client is disconnected may collapse to the latest projection. A resume cursor ahead of current durable state is normalized to the current watermark. Race-free browser bootstrap opens SSE through `ready` before loading the REST snapshot, then merges buffered/live updates by `(monitoringProfileId, normalizedItemId)`. Jdbi-backed polling runs on the blocking executor, while the HTTP controller remains a streaming `Publisher` boundary.

## Runtime configuration

The runnable application enables Results event consumption by default with:

```text
SIGNALHARVESTER_RESULTS_ENABLED=true
SIGNALHARVESTER_RESULTS_CONSUMER_GROUP=signalharvester-results-v1
SIGNALHARVESTER_RESULTS_KAFKA_MAX_ATTEMPTS=3
SIGNALHARVESTER_RESULTS_KAFKA_RETRY_BACKOFF=250ms
SIGNALHARVESTER_RESULTS_DEAD_LETTER_TOPIC=signalharvester.results.analysis-outcome-dead-letter.v1
SIGNALHARVESTER_RESULTS_REPLAY_READ_TIMEOUT=2s
SIGNALHARVESTER_RESULTS_REPLAY_MAX_CONCURRENCY=1
SIGNALHARVESTER_RESULTS_SSE_POLL_INTERVAL=1s
SIGNALHARVESTER_RESULTS_SSE_KEEPALIVE_INTERVAL=15s
SIGNALHARVESTER_RESULTS_SSE_RECONNECT_DELAY=2s
SIGNALHARVESTER_RESULTS_SSE_BATCH_SIZE=100
```

The consumed topics are the existing `SIGNALHARVESTER_KAFKA_ITEM_ANALYZED_TOPIC` and `SIGNALHARVESTER_KAFKA_ITEM_REJECTED_TOPIC` settings. The read REST API is available independently of the consumer toggle when the application serves HTTP.

## Known limitations

- rejected-result persistence remains internal operational state and is not exposed by this product API;
- no transactional outbox/exactly-once cross-resource guarantee;
- automatic/bulk replay remains intentionally absent; accepted ADMIN recovery reprojects one confirmed real DLQ record through Results only.

## Read next

- [`contract.md`](contract.md)
- [`../AGENTS.md`](../AGENTS.md)
- [`../../docs/specs/archive/subspecs/backend-results-sse-live-delivery.md`](../../docs/specs/archive/subspecs/backend-results-sse-live-delivery.md)
- [`../../docs/specs/archive/subspecs/backend-results-rest-api.md`](../../docs/specs/archive/subspecs/backend-results-rest-api.md)
- [`../../docs/IMPLEMENTATION.md`](../../docs/IMPLEMENTATION.md)
