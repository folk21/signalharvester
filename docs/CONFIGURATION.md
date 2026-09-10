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

The current backend runtime supports PostgreSQL, collection HTTP, and Kafka publication environment variables:

| Variable | Default | Purpose |
|---|---:|---|
| `SIGNALHARVESTER_DB_URL` | `jdbc:postgresql://localhost:5432/signalharvester` | JDBC URL for the application PostgreSQL database. |
| `SIGNALHARVESTER_DB_USERNAME` | `signalharvester` | Local-development PostgreSQL username. |
| `SIGNALHARVESTER_DB_PASSWORD` | `signalharvester` | Local-development PostgreSQL password; override outside local development. |
| `SIGNALHARVESTER_DB_MAX_POOL_SIZE` | `10` | Maximum Hikari connections for the default datasource. |
| `SIGNALHARVESTER_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap servers used by Micronaut Kafka clients. |
| `SIGNALHARVESTER_KAFKA_RAW_ITEM_DISCOVERED_TOPIC` | `signalharvester.collection.raw-item-discovered.v1` | Versioned topic for collection raw-item events. |
| `SIGNALHARVESTER_COLLECTION_MAX_CONCURRENCY` | `8` | Maximum concurrently active source-fetch workers. |
| `SIGNALHARVESTER_COLLECTION_HTTP_CONNECT_TIMEOUT` | `3s` | External HTTP connection timeout. |
| `SIGNALHARVESTER_COLLECTION_HTTP_READ_TIMEOUT` | `10s` | Maximum wait for response reads. |
| `SIGNALHARVESTER_COLLECTION_HTTP_REQUEST_TIMEOUT` | `15s` | Overall external HTTP request timeout. |
| `SIGNALHARVESTER_COLLECTION_HTTP_MAX_CONTENT_LENGTH` | `1048576` | Maximum buffered external response size in bytes. |
| `SIGNALHARVESTER_COLLECTION_HTTP_MAX_REDIRECTS` | `5` | Maximum redirects followed by the generic source client. |
| `SIGNALHARVESTER_COLLECTION_HTTP_MAX_CONNECTIONS` | `32` | Maximum pooled HTTP client connections. |
| `SIGNALHARVESTER_COLLECTION_HTTP_MAX_PENDING_ACQUIRES` | `64` | Maximum pending connection-pool acquisitions. |
| `SIGNALHARVESTER_COLLECTION_HTTP_POOL_ACQUIRE_TIMEOUT` | `2s` | Maximum wait for a pooled connection. |

`micronaut.executors.blocking.virtual=true` makes Micronaut's blocking executor Virtual-Thread backed on the Java 21 baseline. Blocking collection workflows and future blocking controller methods use this executor rather than a Netty event loop.

The maximum concurrency value is validated through Micronaut/Jakarta Validation and must be positive. Micronaut may surface non-success HTTP statuses as `HttpClientResponseException`; the collection adapter normalizes that transport behavior into `SourceFetchException` while preserving status and raw `Retry-After` metadata. Redirect following is enabled but bounded. Automatic decompression is enabled, connection pooling is explicit, and `allow-block-event-loop=false` protects against accidental blocking client calls from Netty event-loop threads.

Configured source URLs are domain values: they must be absolute HTTP/HTTPS locations with a host, without embedded user-info credentials and without URI fragments. Secrets should be modeled separately rather than embedded into URLs.

The collection raw-item producer uses `StringSerializer` for Kafka keys and `ByteArraySerializer` for explicit Protobuf payload bytes. It waits for acknowledgements with `acks=all` and enables Kafka producer idempotence. These settings protect producer transport retries; they do not replace application-level replay/idempotency rules.

Secrets must not be committed.

## Persisted application configuration

The configuration module owns source configuration persistence in PostgreSQL. Its first Flyway migration lives under `db/migration/configuration` and creates module-owned `configuration.sources` and `configuration.source_settings` objects. Other modules consume effective configuration through `SourceConfigurationProvider`; they must not read these tables directly.

Source names are not globally unique. The stable `SourceId` is the identity boundary, so two sources may intentionally share a display name while retaining different identifiers, locations, and settings.

Persisting a source URL does not authorize collection from that destination. A configurable outbound destination/SSRF policy is still required before source management can be treated as safe for untrusted users.

## Compatibility

Configuration fields that affect persisted behavior, API contracts, or source interpretation require explicit compatibility consideration and tests when implemented.
