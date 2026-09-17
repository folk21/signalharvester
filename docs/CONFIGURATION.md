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
| `SIGNALHARVESTER_METRICS_ENABLED` | `true` | Enables Micrometer application/runtime metrics. |
| `SIGNALHARVESTER_PROMETHEUS_ENABLED` | `true` | Enables the Prometheus registry and `/prometheus` scrape endpoint. |
| `SIGNALHARVESTER_OTEL_TRACES_EXPORTER` | `none` | OpenTelemetry trace exporter; use `otlp` when an OTLP collector is available. |
| `SIGNALHARVESTER_OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OTLP endpoint used when an OTLP exporter is enabled. |
| `MICRONAUT_ENVIRONMENTS` | _(unset)_ | Include `security` to activate protected JWT/RBAC HTTP boundaries. |
| `SIGNALHARVESTER_JWT_SECRET` | _(required with `security`)_ | HMAC secret used to sign and validate application JWTs; no repository default. |
| `SIGNALHARVESTER_JWT_TTL_SECONDS` | `900` | Access-token lifetime in seconds. |
| `SIGNALHARVESTER_JWT_AUDIENCE` | `signalharvester-api` | Required JWT audience and generated audience claim. |
| `SIGNALHARVESTER_AUTH_COOKIE_SECURE` | `false` | Sets Secure on JWT/CSRF cookies; must be `true` for HTTPS shared/public deployment. |
| `SIGNALHARVESTER_AUTH_COOKIE_MAX_AGE` | `15m` | Browser JWT cookie maximum age. |
| `SIGNALHARVESTER_CSRF_SECRET` | _(required with `security`)_ | Independent HMAC secret for signed double-submit CSRF tokens; no repository default. |
| `SIGNALHARVESTER_BOOTSTRAP_ADMIN_USERNAME` | _(empty)_ | Optional first-ADMIN login used only when no enabled ADMIN exists. |
| `SIGNALHARVESTER_BOOTSTRAP_ADMIN_PASSWORD` | _(empty)_ | Optional first-ADMIN password; must be supplied together with the bootstrap username. |
| `SIGNALHARVESTER_PASSWORD_HASH_ITERATIONS` | `600000` | PBKDF2-HMAC-SHA256 iteration count for newly stored local passwords. |
| `SIGNALHARVESTER_CORS_ENABLED` | `false` | Enables credentialed CORS for an explicitly separate frontend origin. |
| `SIGNALHARVESTER_CORS_ALLOWED_ORIGIN` | `http://localhost:5173` | Single allowed frontend origin when credentialed CORS is enabled. |
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
| `SIGNALHARVESTER_ANALYSIS_OUTBOX_ENABLED` | `true` | Enables background publication of committed Analysis outbox rows. |
| `SIGNALHARVESTER_ANALYSIS_OUTBOX_POLL_INTERVAL` | `1s` | Delay between bounded Analysis outbox dispatch passes. |
| `SIGNALHARVESTER_ANALYSIS_OUTBOX_BATCH_SIZE` | `100` | Maximum outbox rows claimed per pass; bounded to 500. |
| `SIGNALHARVESTER_ANALYSIS_OUTBOX_LEASE_DURATION` | `30s` | Cross-replica claim lease for one dispatch batch; bounded to five minutes. |
| `SIGNALHARVESTER_ANALYSIS_OUTBOX_RETRY_BACKOFF` | `2s` | Delay before a failed outbox row becomes claimable again; bounded to one minute. |
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

### Security runtime

The default runtime keeps Micronaut Security disabled for the trusted local workflow.

Activating the `security` environment loads `application-security.properties`. That profile:

- requires deployment-provided JWT and CSRF signing secrets;
- enables HttpOnly JWT cookies and bearer-token validation;
- applies the explicit endpoint/role matrix;
- enables signed double-submit CSRF.

CORS stays disabled unless `SIGNALHARVESTER_CORS_ENABLED=true`. Credentialed CORS uses one configured origin, never `*`.

The browser JWT cookie is HttpOnly and `SameSite=Strict`. The readable `XSRF-TOKEN` cookie contains only CSRF proof. JSON/form mutations return that proof in `X-CSRF-TOKEN`.

JWTs must not appear in URLs.

Local HTTP development may keep `SIGNALHARVESTER_AUTH_COOKIE_SECURE=false`. Shared/public HTTPS deployment must enable Secure cookies.

Account disablement and role changes affect future login and token issuance. Already-issued stateless JWTs remain valid until their configured short expiry.

### Observability runtime

The composition root enables:

- `/health`;
- `/health/liveness`;
- `/health/readiness`;
- `/prometheus`.

OpenTelemetry uses the standard `tracecontext,baggage` propagators. `otel.traces.exporter` defaults to `none`, so a local OTLP collector is not required for startup.

To export traces, set:

- `SIGNALHARVESTER_OTEL_TRACES_EXPORTER=otlp`;
- `SIGNALHARVESTER_OTEL_EXPORTER_OTLP_ENDPOINT`.

Health and Prometheus paths are excluded from normal HTTP trace noise.

Application metrics use bounded status/outcome labels. They do not use source, profile, run, item, event, or URL identifiers as labels. Prometheus cardinality therefore stays independent from harvested entity count.

### Blocking execution

`micronaut.executors.blocking.virtual=true` makes Micronaut's blocking executor Virtual-Thread backed on Java 21.

Source, collection-admin, Analysis-inspection, Results REST, and Event Observation history controllers use that executor for JDBC and synchronous work. They do not run blocking work on Netty event-loop threads.

Results and Event Observation SSE remain reactive streaming controllers. Their JDBC polling is submitted to the same blocking executor by the stream implementation.

### Collection bounds

Collection concurrency must be positive.

Configured extraction bounds are also validated:

- RSS/Atom and generic JSON/HTML maximum item counts are positive and capped at 10,000;
- Source Test preview cardinality is capped at 50 items;
- Source Test preview content is capped at 5,000 characters per item.

These bounds sit behind the HTTP maximum-response-size limit.

Micronaut may surface non-success HTTP statuses as `HttpClientResponseException`.

The Collection adapter normalizes that transport behavior into `SourceFetchException` while preserving status and raw `Retry-After` metadata.

Redirect following is enabled but bounded. Automatic decompression is enabled. Connection pooling is explicit.

`allow-block-event-loop=false` protects against accidental blocking client calls from Netty event-loop threads.

Configured Source URLs are domain values. They must:

- use absolute HTTP/HTTPS locations;
- include a host;
- omit embedded user-info credentials;
- omit URI fragments.

Secrets belong in separate secret configuration, not in URLs.

### Kafka durability and failure handling

Collection, Analysis outbox, and dead-letter producers use `StringSerializer` for Kafka keys and `ByteArraySerializer` for explicit Protobuf payload bytes. They use `acks=all` and Kafka producer idempotence.

Analysis, Results, and Event Observation use manual source-offset commits.

Failure handling is explicit:

- deterministic decode/key/mapping failures bypass retry and publish `failure/v1/DeadLetterEvent`;
- application failures retry up to the owning configured maximum with fixed backoff;
- Analysis commits deduplication state plus serialized terminal event to PostgreSQL before advancing the raw source offset;
- the Analysis outbox publishes that stored record later;
- Results and Event Observation advance source offsets after durable projection or recording;
- an acknowledged DLQ is the terminal boundary for failed inputs;
- failed DLQ publication leaves the source offset uncommitted.

Event Observation uses its own consumer group and does not compete with business consumers.

Automatic replay is intentionally absent.

### Temporary global analysis configuration

The first analyzer uses a small global keyword list in `application.properties`. `SIGNALHARVESTER_ANALYSIS_MINIMUM_KEYWORD_MATCHES` defines the relevance threshold.

This is temporary runtime configuration until Monitoring Profiles own analysis rules.

The threshold must be positive and must not exceed the number of unique configured keywords.

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

The checked-in `.env.example` also contains `SIGNALHARVESTER_DB_URL` and `SIGNALHARVESTER_KAFKA_BOOTSTRAP_SERVERS`. One local file can therefore be sourced for the host-run backend.

Docker Compose cannot derive a JDBC URL for the backend. When `DB_NAME`, `DB_PORT`, or the Kafka host port changes, keep those runtime endpoint values synchronized explicitly.

Use the env file explicitly:

```bash
cp infra/docker-compose/.env.example infra/docker-compose/.env
docker compose --env-file infra/docker-compose/.env -f infra/docker-compose/compose.yaml up -d
```

The local `.env` file is ignored by Git and excluded from FULL archives.

## Persisted application configuration

The Configuration module owns Source and Monitoring Profile persistence in PostgreSQL. Its Flyway migrations under `db/migration/configuration` create:

- Source configuration;
- Monitoring Profiles;
- ordered Source membership;
- profile criteria.

Other modules own their own state:

- Analysis — deduplication state under `db/migration/analysis` and schema `analysis`;
- Collection — durable run history and Monitoring Profile schedule state under `db/migration/collection` and schema `collection`;
- Results — analyzed/rejected projections and durable live-result cursors under `db/migration/results` and schema `results`;
- Security — identities and explicit roles under `db/migration/security` and schema `security`.

All module migration locations use one datasource and one Flyway schema history. Migration version numbers must therefore remain globally unique across those locations.

Other modules consume effective configuration through `SourceConfigurationProvider` and `MonitoringProfileConfigurationProvider`. They must not read Configuration tables directly.

A Monitoring Profile stores:

- a positive collection interval;
- at least one existing Source ID;
- string criteria.

Enabled profiles are polled by Collection scheduling. Scheduler state remains Collection-owned and is not stored in Configuration tables.

Disabling a profile prevents new automatic claims. Manual execution may still target an existing profile explicitly.

Source names are not globally unique. The stable `SourceId` is the identity boundary, so two sources may intentionally share a display name while retaining different identifiers, locations, and settings.

### Persisted source extraction settings

`source_settings` stores string values. Collection interprets the additive keys below.

A REST or HTML Source with no matching extraction prefix keeps the original one-response passthrough behavior. Unknown settings outside these prefixes remain opaque to Collection.

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

The following are explicit extraction failures:

- malformed pointers/selectors;
- invalid extracted URLs or timestamps;
- missing required mappings;
- candidate sets above the configured bound.

Use the Source Test API before enabling a new Source to validate these settings against a real response.

Persisting a source URL does not authorize collection from that destination. Collection owns the accepted runtime destination authorization boundary.

Outbound-source settings:

| Setting | Default | Meaning |
|---|---|---|
| `signalharvester.collection.outbound-access.mode` | `TRUSTED_LOCAL` | `TRUSTED_LOCAL` preserves deterministic loopback/private development sources; `SECURE` blocks private, carrier-grade NAT/shared, loopback, link-local, IPv6 unique-local, unspecified, and multicast destination classes; CIDR overrides apply only to intentionally allowed internal ranges. The `security` environment forces `SECURE`. |
| `signalharvester.collection.outbound-access.allowed-cidrs` | empty | Comma-separated IPv4/IPv6 CIDR ranges that explicitly authorize otherwise blocked internal destinations in `SECURE` mode. |
| `micronaut.http.client.address-resolver-group-name` | `signalharvester-external-source-access` in the runnable app | Binds DNS authorization to the Netty address resolver used by the actual connection path. |

Runnable-backend environment variables are:

- `SIGNALHARVESTER_COLLECTION_OUTBOUND_ACCESS_MODE`;
- `SIGNALHARVESTER_COLLECTION_OUTBOUND_ALLOWED_CIDRS`.

Shared/security deployments should keep `SECURE` mode and add only the narrowest CIDR rules required for intentional internal Sources. Unspecified/any-local and multicast addresses remain rejected.

## Compatibility

Configuration fields that affect persisted behavior, API contracts, or source interpretation require explicit compatibility consideration and tests when implemented.

## Kubernetes runtime configuration

The accepted local Kubernetes stack sets non-secret backend values through `infra/kubernetes/kustomization.yaml`.

It requires deployment-owned Secret objects:

- `signalharvester-runtime-secrets`;
- `signalharvester-observability-secrets`.

`infra/kubernetes/create-local-secrets.sh` creates these Secrets from explicit environment values or generated local values. It does not write credentials to repository files.

The backend Kubernetes ConfigMap activates:

- `MICRONAUT_ENVIRONMENTS=security`;
- PostgreSQL at `postgres:5432`;
- Redpanda at `redpanda:9092`;
- OTLP trace export to `tempo:4317`;
- the localhost CORS origin used by the documented frontend port-forward workflow.

The local stack sets `SIGNALHARVESTER_AUTH_COOKIE_SECURE=false` because it intentionally uses HTTP port forwarding.

Shared/public Internet exposure must terminate TLS and enable Secure cookies. Do not copy the local HTTP exception into production.
