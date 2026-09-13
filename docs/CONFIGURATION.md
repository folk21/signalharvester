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
| `SIGNALHARVESTER_KAFKA_RAW_ITEM_DISCOVERED_TOPIC` | `signalharvester.collection.raw-item-discovered.v1` | Versioned topic for collection raw-item events. |
| `SIGNALHARVESTER_KAFKA_ITEM_ANALYZED_TOPIC` | `signalharvester.analysis.item-analyzed.v1` | Versioned topic for accepted analyzed items. |
| `SIGNALHARVESTER_KAFKA_ITEM_REJECTED_TOPIC` | `signalharvester.analysis.item-rejected.v1` | Versioned topic for analysis rejection outcomes such as duplicates. |
| `SIGNALHARVESTER_ANALYSIS_ENABLED` | `true` | Enables the raw-item analysis Kafka listener. |
| `SIGNALHARVESTER_ANALYSIS_CONSUMER_GROUP` | `signalharvester-analysis-v1` | Consumer group for the analysis raw-item listener. |
| `SIGNALHARVESTER_RESULTS_ENABLED` | `true` | Enables Results consumption/materialization of terminal Analysis events. |
| `SIGNALHARVESTER_RESULTS_CONSUMER_GROUP` | `signalharvester-results-v1` | Consumer group for the Results terminal-analysis listeners. |
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

`micronaut.executors.blocking.virtual=true` makes Micronaut's blocking executor Virtual-Thread backed on the Java 21 baseline. The current source, collection-admin, and analysis-inspection controllers use this executor for JDBC and synchronous collection workflows rather than running blocking work on a Netty event loop.

Collection concurrency is validated as positive. RSS/Atom extraction also validates a positive maximum entry count (capped at 10,000) so one fetched XML response cannot create unbounded item cardinality. Micronaut may surface non-success HTTP statuses as `HttpClientResponseException`; the collection adapter normalizes that transport behavior into `SourceFetchException` while preserving status and raw `Retry-After` metadata. Redirect following is enabled but bounded. Automatic decompression is enabled, connection pooling is explicit, and `allow-block-event-loop=false` protects against accidental blocking client calls from Netty event-loop threads.

Configured source URLs are domain values: they must be absolute HTTP/HTTPS locations with a host, without embedded user-info credentials and without URI fragments. Secrets should be modeled separately rather than embedded into URLs.

Collection and analysis producers use `StringSerializer` for Kafka keys and `ByteArraySerializer` for explicit Protobuf payload bytes. They wait for acknowledgements with `acks=all` and enable Kafka producer idempotence. Analysis and Results consume String-keyed byte-array payloads with explicit offset commit only after their durable application processing succeeds. These transport settings do not replace application-level replay/idempotency rules.

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

The configuration module owns source configuration persistence in PostgreSQL. Its first Flyway migration lives under `db/migration/configuration` and creates module-owned `configuration.sources` and `configuration.source_settings` objects. Analysis owns deduplication state under `db/migration/analysis` and schema `analysis`; collection owns durable run history under `db/migration/collection` and schema `collection`; Results owns analyzed/rejected projections under `db/migration/results` and schema `results`. All module migration locations are applied through one datasource and one Flyway schema history, so migration version numbers must remain globally unique across those locations. Other modules consume effective configuration through `SourceConfigurationProvider`; they must not read configuration tables directly.

Source names are not globally unique. The stable `SourceId` is the identity boundary, so two sources may intentionally share a display name while retaining different identifiers, locations, and settings.

Persisting a source URL does not authorize collection from that destination. A configurable outbound destination/SSRF policy is still required before source management can be treated as safe for untrusted users.

## Compatibility

Configuration fields that affect persisted behavior, API contracts, or source interpretation require explicit compatibility consideration and tests when implemented.
