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

The current backend runtime supports these collection-related environment variables:

| Variable | Default | Purpose |
|---|---:|---|
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

The maximum concurrency value is validated through Micronaut/Jakarta Validation and must be positive. External HTTP error statuses are returned to the collection adapter rather than automatically raised by the client, which lets the module preserve status and `Retry-After` metadata. Redirect following is enabled but bounded. Automatic decompression is enabled, connection pooling is explicit, and `allow-block-event-loop=false` protects against accidental blocking client calls from Netty event-loop threads.

Configured source URLs are domain values: they must be absolute HTTP/HTTPS locations with a host, without embedded user-info credentials and without URI fragments. Secrets should be modeled separately rather than embedded into URLs.

Secrets must not be committed.

## Persisted application configuration

The configuration module will own application-level configuration and its PostgreSQL migrations. Other modules must consume it through an explicit module API or event flow rather than reading configuration tables directly.

## Compatibility

Configuration fields that affect persisted behavior, API contracts, or source interpretation require explicit compatibility consideration and tests when implemented.
