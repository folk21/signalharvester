---
type: Configuration Guide
title: Configuration
description: Ownership and intended semantics of runtime environment configuration and persisted SignalHarvester application configuration.
---
# Configuration

## Ownership

SignalHarvester has two distinct configuration categories:

1. **deployment/runtime configuration** — ports, database/Kafka endpoints, credentials, telemetry endpoints, and similar environment-specific settings;
2. **application configuration** — monitoring profiles, external sources, schedules, filters, extraction settings, and analysis settings persisted by the configuration module.

Do not mix these categories or hardcode values that belong to either one.

## Runtime configuration

The current backend runtime supports PostgreSQL, collection HTTP, Kafka publication/consumption, and deterministic analysis environment variables:

| Variable | Default | Purpose |
|---|---:|---|
| `SIGNALHARVESTER_HTTP_PORT` | `8080` | Backend HTTP server port. |
| `SIGNALHARVESTER_DB_URL` | `jdbc:postgresql://localhost:5432/signalharvester` | JDBC URL for the application PostgreSQL database. |
| `SIGNALHARVESTER_DB_USERNAME` | `signalharvester` | Local-development PostgreSQL username. |
| `SIGNALHARVESTER_DB_PASSWORD` | `signalharvester` | Local-development PostgreSQL password; override outside local development. |
| `SIGNALHARVESTER_DB_MAX_POOL_SIZE` | `10` | Maximum Hikari connections for the default datasource. |
| `SIGNALHARVESTER_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap servers used by Micronaut Kafka clients. |
| `SIGNALHARVESTER_COLLECTION_RSS_MAX_ITEMS_PER_SOURCE` | `500` | Maximum RSS/Atom entries accepted from one fetched response; values above the bound fail extraction explicitly. |
| `SIGNALHARVESTER_COLLECTION_EXTRACTION_MAX_ITEMS_PER_SOURCE` | `500` | Maximum candidate items accepted from one configuration-driven REST/JSON or HTML response. |
| `SIGNALHARVESTER_COLLECTION_SOURCE_TEST_MAX_PREVIEW_ITEMS` | `5` | Maximum extracted items included in one diagnostic source-test response. |
| `SIGNALHARVESTER_COLLECTION_SOURCE_TEST_MAX_PREVIEW_CONTENT_CHARS` | `500` | Maximum content characters included for each diagnostic preview item. |
| `SIGNALHARVESTER_COLLECTION_SCHEDULER_ENABLED` | `true` | Enables automatic polling of persisted enabled monitoring profiles. |
| `SIGNALHARVESTER_COLLECTION_SCHEDULER_POLL_INTERVAL` | `10s` | Delay between scheduler polls. |
| `SIGNALHARVESTER_COLLECTION_SCHEDULER_INITIAL_DELAY` | `10s` | Delay before the first scheduler poll after startup. |
| `SIGNALHARVESTER_COLLECTION_SCHEDULER_LEASE_DURATION` | `2m` | PostgreSQL lease duration for one scheduled profile run. |
| `SIGNALHARVESTER_COLLECTION_SCHEDULER_HEARTBEAT_INTERVAL` | `30s` | Renewal interval for an active schedule lease; must be shorter than the lease duration. |
| `SIGNALHARVESTER_KAFKA_RAW_ITEM_DISCOVERED_TOPIC` | `signalharvester.collection.raw-item-discovered.v1` | Versioned topic for collection raw-item events. |
| `SIGNALHARVESTER_KAFKA_ITEM_ANALYZED_TOPIC` | `signalharvester.analysis.item-analyzed.v1` | Versioned topic for accepted analyzed items. |
| `SIGNALHARVESTER_KAFKA_ITEM_REJECTED_TOPIC` | `signalharvester.analysis.item-rejected.v1` | Versioned topic for analysis rejection outcomes such as duplicates. |
| `SIGNALHARVESTER_ANALYSIS_ENABLED` | `true` | Enables the raw-item analysis Kafka listener. |
| `SIGNALHARVESTER_ANALYSIS_CONSUMER_GROUP` | `signalharvester-analysis-v1` | Consumer group for the analysis raw-item listener. |
| `SIGNALHARVESTER_ANALYSIS_KAFKA_MAX_ATTEMPTS` | `3` | Maximum Analysis processing attempts for a validated Kafka record, including the initial attempt; bounded to 10. |
| `SIGNALHARVESTER_ANALYSIS_KAFKA_RETRY_BACKOFF` | `250ms` | Fixed delay between retryable Analysis Kafka attempts; `0ms` through `5s`. |
| `SIGNALHARVESTER_ANALYSIS_DEAD_LETTER_TOPIC` | `signalharvester.analysis.raw-item-dead-letter.v1` | Terminal DLQ for invalid or retry-exhausted Analysis inputs. |
| `SIGNALHARVESTER_RESULTS_ENABLED` | `true` | Enables Results consumption/materialization of terminal Analysis events. |
| `SIGNALHARVESTER_RESULTS_CONSUMER_GROUP` | `signalharvester-results-v1` | Consumer group for the Results terminal-analysis listeners. |
| `SIGNALHARVESTER_RESULTS_KAFKA_MAX_ATTEMPTS` | `3` | Maximum Results projection attempts for a validated Kafka record, including the initial attempt; bounded to 10. |
| `SIGNALHARVESTER_RESULTS_KAFKA_RETRY_BACKOFF` | `250ms` | Fixed delay between retryable Results Kafka attempts; `0ms` through `5s`. |
| `SIGNALHARVESTER_RESULTS_DEAD_LETTER_TOPIC` | `signalharvester.results.analysis-outcome-dead-letter.v1` | Terminal DLQ for invalid or retry-exhausted Results inputs. |
| `SIGNALHARVESTER_RESULTS_SSE_POLL_INTERVAL` | `1s` | Delay between durable Results live-cursor polls for an open SSE subscription. |
| `SIGNALHARVESTER_RESULTS_SSE_KEEPALIVE_INTERVAL` | `15s` | Maximum idle period before a `keepalive` SSE event is emitted. |
| `SIGNALHARVESTER_RESULTS_SSE_RECONNECT_DELAY` | `2s` | Browser reconnect delay written to SSE `retry` on ready/result events. |
| `SIGNALHARVESTER_RESULTS_SSE_BATCH_SIZE` | `100` | Maximum live result updates read per poll; bounded to 200. |
| `SIGNALHARVESTER_EVENT_OBSERVATION_ENABLED` | `true` | Enables the independent technical event-observation Kafka consumer. |
| `SIGNALHARVESTER_EVENT_OBSERVATION_CONSUMER_GROUP` | `signalharvester-event-observation-v1` | Consumer group used only by Event Observation. |
| `SIGNALHARVESTER_EVENT_OBSERVATION_KAFKA_MAX_ATTEMPTS` | `3` | Maximum Event Observation recording attempts for a validated Kafka record, including the initial attempt; bounded to 10. |
| `SIGNALHARVESTER_EVENT_OBSERVATION_KAFKA_RETRY_BACKOFF` | `250ms` | Fixed delay between retryable Event Observation Kafka attempts; `0ms` through `5s`. |
| `SIGNALHARVESTER_EVENT_OBSERVATION_DEAD_LETTER_TOPIC` | `signalharvester.event-observation.dead-letter.v1` | Terminal DLQ for invalid or retry-exhausted Event Observation inputs. |
| `SIGNALHARVESTER_EVENT_OBSERVATION_MAX_EVENTS` | `10000` | Maximum retained diagnostic event rows. |
| `SIGNALHARVESTER_EVENT_OBSERVATION_MAX_AGE` | `24h` | Maximum age of retained diagnostic event rows. |
| `SIGNALHARVESTER_EVENT_OBSERVATION_SSE_POLL_INTERVAL` | `1s` | Delay between durable event-history polls for an open Event Explorer SSE subscription. |
| `SIGNALHARVESTER_EVENT_OBSERVATION_SSE_KEEPALIVE_INTERVAL` | `15s` | Maximum idle period before an Event Explorer `keepalive` SSE event. |
| `SIGNALHARVESTER_EVENT_OBSERVATION_SSE_RECONNECT_DELAY` | `2s` | Browser reconnect delay written to Event Explorer SSE `retry`. |
| `SIGNALHARVESTER_EVENT_OBSERVATION_SSE_BATCH_SIZE` | `200` | Maximum observed events read per live poll; bounded to 500. |
| `SIGNALHARVESTER_ANALYSIS_MINIMUM_KEYWORD_MATCHES` | `1` | Minimum configured keyword matches required for relevance. |
| `SIGNALHARVESTER_COLLECTION_MAX_CONCURRENCY` | `8` | Maximum concurrently active source-fetch workers. |
| `SIGNALHARVESTER_COLLECTION_HTTP_CONNECT_TIMEOUT` | `3s` | External HTTP connection timeout. |
| `SIGNALHARVESTER_COLLECTION_HTTP_READ_TIMEOUT` | `10s` | Maximum wait for response reads. |
| `SIGNALHARVESTER_COLLECTION_HTTP_REQUEST_TIMEOUT` | `15s` | Overall external HTTP request timeout. |
| `SIGNALHARVESTER_COLLECTION_HTTP_MAX_CONTENT_LENGTH` | `1048576` | Maximum buffered external response size in bytes. |
| `SIGNALHARVESTER_COLLECTION_HTTP_MAX_REDIRECTS` | `5` | Maximum redirects followed by the generic source client. |
| `SIGNALHARVESTER_COLLECTION_HTTP_MAX_CONNECTIONS` | `32` | Maximum pooled HTTP client connections. |
| `SIGNALHARVESTER_COLLECTION_HTTP_MAX_PENDING_ACQUIRES` | `64` | Maximum pending connection-pool acquisitions. |
| `SIGNALHARVESTER_COLLECTION_HTTP_POOL_ACQUIRE_TIMEOUT` | `2s` | Maximum wait for a pooled connection. |

`micronaut.executors.blocking.virtual=true` makes Micronaut's blocking executor Virtual-Thread backed on the Java 21 baseline. The current source, collection-admin, analysis-inspection, Results REST, and Event Observation history controllers use this executor for JDBC and synchronous workflows rather than running blocking work on a Netty event loop. Results and Event Observation SSE remain reactive streaming controllers; their JDBC polling is submitted to the same blocking executor by the stream implementation.

Collection concurrency is validated as positive. RSS/Atom and generic JSON/HTML extraction validate positive maximum item counts capped at 10,000. Source-test preview cardinality is capped at 50 items and preview content at 5,000 characters per item. These bounds sit behind the existing HTTP maximum-response-size limit. Micronaut may surface non-success HTTP statuses as `HttpClientResponseException`; the collection adapter normalizes that transport behavior into `SourceFetchException` while preserving status and raw `Retry-After` metadata. Redirect following is enabled but bounded. Automatic decompression is enabled, connection pooling is explicit, and `allow-block-event-loop=false` protects against accidental blocking client calls from Netty event-loop threads.

Configured source URLs are domain values: they must be absolute HTTP/HTTPS locations with a host, without embedded user-info credentials and without URI fragments. Secrets should be modeled separately rather than embedded into URLs.

Collection, analysis, and dead-letter producers use `StringSerializer` for Kafka keys and `ByteArraySerializer` for explicit Protobuf payload bytes. They wait for acknowledgements with `acks=all` and enable Kafka producer idempotence. Analysis, Results, and Event Observation use manual source-offset commits. Deterministic decode/key/mapping failures bypass retry and publish a `failure/v1/DeadLetterEvent`; application failures after successful decode/key/mapping retry up to the owning configured maximum with fixed backoff. A source offset advances only after normal processing or acknowledged DLQ publication. If DLQ publication fails, the source offset remains uncommitted. Event Observation uses its own consumer group and therefore does not compete with business consumers. Automatic replay is intentionally absent in this increment.

The first analyzer uses a small global keyword list in `application.properties` and `SIGNALHARVESTER_ANALYSIS_MINIMUM_KEYWORD_MATCHES` for the threshold. This is temporary runtime configuration until monitoring profiles own analysis rules. The threshold must be positive and cannot exceed the number of unique configured keywords.

Secrets must not be committed.

## Local Docker Compose overrides

`infra/docker-compose/compose.yaml` provides safe defaults but is parameterized for developer-machine conflicts and local experiments. Its supported infrastructure overrides are:

| Variable | Default | Compose purpose |
|---|---:|---|
| `SIGNALHARVESTER_DB_NAME` | `signalharvester` | PostgreSQL database created by the local container. |
| `SIGNALHARVESTER_DB_USERNAME` | `signalharvester` | PostgreSQL local username. |
| `SIGNALHARVESTER_DB_PASSWORD` | `signalharvester` | PostgreSQL local password. |
| `SIGNALHARVESTER_DB_PORT` | `5432` | PostgreSQL host port. |
| `SIGNALHARVESTER_KAFKA_ADVERTISED_HOST` | `localhost` | Host Redpanda advertises to host-run Kafka clients. |
| `SIGNALHARVESTER_KAFKA_PORT` | `9092` | Kafka API host port. |
| `SIGNALHARVESTER_REDPANDA_ADMIN_PORT` | `9644` | Redpanda Admin API host port. |

The checked-in `.env.example` also contains `SIGNALHARVESTER_DB_URL` and `SIGNALHARVESTER_KAFKA_BOOTSTRAP_SERVERS` so one local file can be sourced for the host-run backend. Docker Compose cannot derive a JDBC URL for the backend, so when `DB_NAME`, `DB_PORT`, or the Kafka host port changes, keep those runtime endpoint values synchronized explicitly.

Use the env file explicitly:

```bash
cp infra/docker-compose/.env.example infra/docker-compose/.env
docker compose --env-file infra/docker-compose/.env -f infra/docker-compose/compose.yaml up -d
```

The local `.env` file is ignored by Git and excluded from FULL archives.

## Persisted application configuration

The configuration module owns source and monitoring-profile persistence in PostgreSQL. Its Flyway migrations under `db/migration/configuration` create source configuration plus monitoring profiles, ordered source membership, and profile criteria. Analysis owns deduplication state under `db/migration/analysis` and schema `analysis`; collection owns durable run history and monitoring-profile schedule state under `db/migration/collection` and schema `collection`; Results owns analyzed/rejected projections and durable live-result cursors under `db/migration/results` and schema `results`. All module migration locations are applied through one datasource and one Flyway schema history, so migration version numbers must remain globally unique across those locations. Other modules consume effective configuration through `SourceConfigurationProvider` and `MonitoringProfileConfigurationProvider`; they must not read configuration tables directly.

A monitoring profile stores a positive collection interval, at least one existing source id, and string criteria. Enabled profiles are polled by Collection scheduling. Scheduler state remains collection-owned and is not stored in configuration tables. Disabling a profile prevents new automatic claims; manual execution may still target an existing profile explicitly.

Source names are not globally unique. The stable `SourceId` is the identity boundary, so two sources may intentionally share a display name while retaining different identifiers, locations, and settings.

### Persisted source extraction settings

`source_settings` stores string values. Collection interprets the following additive keys. A REST or HTML source with no matching extraction prefix keeps the original one-response passthrough behavior. Unknown settings outside these prefixes remain opaque to Collection.

REST JSON extraction is enabled when any `json.*` key is present:

| Setting | Required | Meaning |
|---|---|---|
| `json.itemsPointer` | no | RFC 6901 JSON Pointer to the candidate array or object. Empty/root is the default. |
| `json.contentPointer` | yes when JSON extraction is enabled | Pointer relative to each candidate for semantic content. |
| `json.externalIdPointer` | no | Optional scalar external identity. |
| `json.titlePointer` | no | Optional scalar title. |
| `json.urlPointer` | no | Optional scalar absolute or relative HTTP(S) item URL. |
| `json.publishedAtPointer` | no | Optional scalar ISO/RFC-compatible publication timestamp. |

HTML extraction is enabled when any `html.*` key is present:

| Setting | Required | Meaning |
|---|---|---|
| `html.itemSelector` | yes when HTML extraction is enabled | CSS selector for repeated candidate elements. |
| `html.contentSelector` | no | Selector relative to the candidate; candidate text is used when omitted. |
| `html.titleSelector` | no | Optional title selector. |
| `html.externalIdSelector` | no | Optional identity selector relative to the candidate. |
| `html.externalIdAttribute` | no | Attribute to read for identity; when no selector is supplied the candidate element is used. |
| `html.urlSelector` | no | Optional link selector relative to the candidate. |
| `html.urlAttribute` | no | URL attribute, default `href`. |
| `html.publishedAtSelector` | no | Optional publication-time selector. |
| `html.publishedAtAttribute` | no | Attribute containing publication time; text is used when omitted. |

Malformed pointers/selectors, invalid extracted URLs/timestamps, missing required mappings, and candidate sets above the configured bound are explicit extraction failures. Use the source-test API before enabling a new source to validate these settings against a real response.

Persisting a source URL does not authorize collection from that destination. A configurable outbound destination/SSRF policy is still required before source management can be treated as safe for untrusted users.

## Compatibility

Configuration fields that affect persisted behavior, API contracts, or source interpretation require explicit compatibility consideration and tests when implemented.
