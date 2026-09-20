---
type: Usage Guide
title: Usage
description: Current backend run and developer workflows for SignalHarvester.
---
# Usage

## Scope

This document owns backend operational workflows and commands. UI-specific workflows belong to the separate `signalharvester-web` project.

## Start local infrastructure

From the repository root:

```bash
docker compose -f infra/docker-compose/compose.yaml up -d
```

The defaults require no `.env` file. For customized local ports or credentials, follow [`../infra/docker-compose/README.md`](../infra/docker-compose/README.md) and use the same explicit `--env-file` for Compose lifecycle commands.

Wait until PostgreSQL and Redpanda report healthy before starting the backend:

```bash
docker compose -f infra/docker-compose/compose.yaml ps
```

## Run the backend

```bash
./gradlew :app:run --no-watch-fs
```

If archive extraction did not preserve executable permission:

```bash
bash ./gradlew :app:run --no-watch-fs
```

The current server port defaults to `8080` and can be overridden:

```bash
SIGNALHARVESTER_HTTP_PORT=8081 ./gradlew :app:run
```

Flyway applies Configuration, Analysis, Collection, Results, Event Observation, and Security migrations at startup.

The backend exposes:

- Source CRUD and diagnostic testing under `/api/v1/sources`;
- Monitoring Profile CRUD, including typed keyword Analysis settings, under `/api/v1/monitoring-profiles`;
- Collection Run operations under `/api/v1/admin/collection-runs`;
- `DIAGNOSTICS.ANALYSIS_INSPECTION` under `/api/v1/admin/analysis/items`.

The Analysis Kafka listener starts by default and waits for `RawItemDiscovered`. The Results listener also starts by default and materializes terminal `ItemAnalyzed` / `ItemRejected` events into PostgreSQL.

### Run with authentication and RBAC

The default local profile remains trusted-environment compatibility mode while frontend authentication work is pending. Do not expose that mode publicly.

To exercise the protected backend boundary, activate the `security` environment and provide deployment-owned secrets:

```bash
MICRONAUT_ENVIRONMENTS=security \
SIGNALHARVESTER_JWT_SECRET='replace-with-a-long-random-jwt-secret' \
SIGNALHARVESTER_CSRF_SECRET='replace-with-an-independent-long-random-csrf-secret' \
SIGNALHARVESTER_BOOTSTRAP_ADMIN_USERNAME='admin' \
SIGNALHARVESTER_BOOTSTRAP_ADMIN_PASSWORD='replace-with-a-strong-password' \
./gradlew :app:run --no-watch-fs
```

Bootstrap credentials are used only when no persisted `ADMIN` exists. The created human account receives explicit `USER`, `VIEWER`, and `ADMIN` roles. There is no repository default administrator password and no login-session table.

Login and keep the browser-style JWT/CSRF cookies in a temporary cookie jar:

```bash
mkdir -p build/tmp
curl -i -c build/tmp/auth.cookies \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"replace-with-a-strong-password"}' \
  http://localhost:8080/api/v1/auth/login
```

Read the authenticated principal:

```bash
curl -s -b build/tmp/auth.cookies http://localhost:8080/api/v1/auth/me
```

For a cookie-authenticated mutation, copy the signed CSRF cookie into the configured header:

```bash
csrf_token=$(awk '$6 == "XSRF-TOKEN" { print $7 }' build/tmp/auth.cookies)
curl -i -b build/tmp/auth.cookies \
  -H "X-CSRF-TOKEN: $csrf_token" \
  -H 'Content-Type: application/json' \
  -d '{"username":"viewer","password":"replace-with-a-strong-password","identityType":"HUMAN","enabled":true,"roles":["VIEWER"]}' \
  http://localhost:8080/api/v1/admin/users
```

A human `VIEWER` also has baseline `USER`. `ADMIN` does not imply `VIEWER`; assign both when an administrator must use the consumer Results surface. Native SSE uses the same HttpOnly cookie and does not put JWT credentials in the URL:

```bash
curl -N -b build/tmp/auth.cookies http://localhost:8080/api/v1/results/stream
```

`/health`, `/health/**`, and `/prometheus` remain anonymously reachable in this local security slice. See [`CONFIGURATION.md`](CONFIGURATION.md) for cookie, token lifetime, CORS, and bootstrap settings.

Example source creation:

```bash
curl -i -X POST http://localhost:8080/api/v1/sources \
  -H 'Content-Type: application/json' \
  -d '{"name":"Jobs API","type":"REST","location":"https://example.test/jobs","enabled":true,"settings":{"query":"java backend"}}'
```

List persisted sources:

```bash
curl http://localhost:8080/api/v1/sources
```

Test a persisted source without publishing Kafka events or creating collection-run history:

```bash
curl -i -X POST http://localhost:8080/api/v1/sources/<SOURCE_UUID>/test
```

A Source may remain `enabled=false` while it is being tested.

When the persisted Source exists, fetch/extraction problems are returned in the diagnostic JSON payload rather than as endpoint failure:

- `FETCH_FAILED`;
- `EXTRACTION_FAILED`.

See [`CONFIGURATION.md`](CONFIGURATION.md) for `json.*` and `html.*` extraction settings.

Example REST/JSON settings for an API that returns `{ "jobs": [...] }`:

```json
{
  "json.itemsPointer": "/jobs",
  "json.externalIdPointer": "/id",
  "json.titlePointer": "/title",
  "json.urlPointer": "/url",
  "json.contentPointer": "/description",
  "json.publishedAtPointer": "/publishedAt"
}
```

Source URLs stored through this API are configuration data only. Runtime Collection applies the accepted outbound destination policy from [`CONFIGURATION.md`](CONFIGURATION.md).

The default trusted-local environment permits loopback/private fixtures. The `security` environment uses `SECURE` mode and rejects blocked destination classes unless the operator explicitly allows the required CIDR.

### Bootstrap sources from a manifest

For repeatable local/trial setup, [`../tools/source-import/`](../tools/source-import/) provides a Python standard-library importer that uses the same `/api/v1/sources` REST boundary rather than writing configuration tables directly.

Preview an import without creating sources:

```bash
python3 tools/source-import/import_sources.py \
  --file tools/source-import/sources.example.json \
  --base-url http://localhost:8080 \
  --dry-run
```

Create only source identities that are currently missing:

```bash
python3 tools/source-import/import_sources.py \
  --file path/to/sources.json \
  --base-url http://localhost:8080
```

The importer matches by Source type plus normalized location. It skips existing identities and does not reconcile changed names, settings, or enabled flags.

Read [`../tools/source-import/README.md`](../tools/source-import/README.md) for manifest versioning, normalization, failure semantics, and security constraints.

The Collection Run workflow is:

1. Resolve one persisted Monitoring Profile through the Configuration API, including its effective Analysis settings.
2. Preserve configured Source order and skip disabled member Sources.
3. Perform bounded best-effort fetches.
4. Extract semantic items.
5. Persist the completed run snapshot.
6. Publish successful items to Kafka with the profile's Analysis settings captured in each `RawItemDiscovered`.

Enabled profiles are also scheduled from their persisted collection interval through Collection-owned PostgreSQL leases.

Extraction behavior is:

- RSS/Atom — one item per feed entry up to the configured bound;
- REST — optional candidate extraction through persisted `json.*` JSON Pointer settings;
- HTML — optional candidate extraction through persisted `html.*` CSS selector settings;
- REST/HTML without those settings — one passthrough item per response.

Analysis consumes the raw events, normalizes and deduplicates them, runs deterministic keyword analysis, and atomically stages `ItemAnalyzed` or `ItemRejected` bytes in the Analysis outbox.

The outbox dispatcher publishes committed records to Kafka. Results consumes terminal events, persists an idempotent Results-owned projection, and exposes analyzed results through REST and resumable SSE. `GET /api/v1/results` also supports optional `search` and opaque `cursor` parameters. The response body remains the existing Result summary array; when more rows exist, read the `X-Next-Cursor` response header and pass it back as `cursor` for the next page. The cursor is tied to the filters/search expression that produced it, while `limit` may change between pages.

## Inspect application observability

Check aggregate health and Kubernetes-style probe endpoints:

```bash
curl -s http://localhost:8080/health
curl -s http://localhost:8080/health/liveness
curl -s http://localhost:8080/health/readiness
```

Inspect Prometheus-format runtime and application metrics:

```bash
curl -s http://localhost:8080/prometheus
```

Application metrics cover:

- Collection Run and source-fetch outcomes/durations;
- Analysis processing and outbox publication outcomes.

Their labels are intentionally low-cardinality. Use Event Explorer or Processing Flow to inspect one concrete run, item, or event.

Trace export is disabled by default. When an OTLP collector is available, enable it for the backend process:

```bash
SIGNALHARVESTER_OTEL_TRACES_EXPORTER=otlp \
SIGNALHARVESTER_OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 \
./gradlew :app:run --no-watch-fs
```

Console logs include `trace_id` and `span_id` fields when a valid OpenTelemetry span is active. The backend still writes application logs to stdout/stderr; use shell redirection or the deployment logging stack for file/storage collection.

## Run the production-style local Kubernetes stack

The backend-owned Kubernetes deployment and resilience workflow are accepted.

To recreate or re-verify the local cluster:

1. build/load `signalharvester-backend:local`;
2. create local runtime secrets;
3. apply the Kustomize target;
4. run live cluster verification.

```bash
docker build -f app/Dockerfile -t signalharvester-backend:local .
./infra/kubernetes/create-local-secrets.sh
kubectl apply -k infra/kubernetes
./infra/kubernetes/verify-local.sh
```

Port-forward the backend and Grafana when interactive inspection is needed:

```bash
kubectl -n signalharvester port-forward service/signalharvester-backend 8080:8080
kubectl -n signalharvester port-forward service/grafana 3000:3000
```

Grafana is provisioned with Prometheus, Loki, Tempo, and the **SignalHarvester Overview** dashboard.

Use Event Explorer or Processing Flow for one concrete run or item. Use Grafana for aggregate runtime and infrastructure behavior.

Full Kubernetes platform acceptance additionally requires a real `signalharvester-web` image through the separate `infra/kubernetes/frontend` workload boundary.

### Run controlled resilience acceptance

After the backend/infrastructure stack is healthy, run the opt-in live resilience harness:

```bash
python3 infra/kubernetes/resilience/run_acceptance.py
```

The harness deploys a temporary in-cluster RSS fixture and exercises:

- backend container restart;
- slow-source availability;
- PostgreSQL outage with bounded Analysis retry/DLQ;
- Kafka lag and recovery;
- Analysis outbox recovery;
- scheduler leases across two replicas;
- Redpanda restart recovery;
- authorization boundaries;
- Prometheus, Loki, and Tempo evidence.

It restores temporary backend environment overrides and removes fixture/profile/source resources on exit. See [`../infra/kubernetes/resilience/README.md`](../infra/kubernetes/resilience/README.md) for fault-injection boundaries and options.

### Demonstrate Kafka consumer horizontal scaling

After the resilience workflow passes, demonstrate R26/S9 with:

```bash
python3 infra/kubernetes/scaling/run_acceptance.py
```

The default scaling run:

1. creates a bounded 6,000-item backlog;
2. confirms positive Analysis lag with one worker;
3. scales the existing backend Deployment to three replicas;
4. verifies three active Analysis consumers own the three raw-event partitions;
5. verifies Results and Event Observation also have at least three active consumer-group members;
6. waits for the backlog to drain;
7. verifies durable Analysis/Results completeness, outbox completion, and DLQ stability;
8. restores the original replica count.

See [`../infra/kubernetes/scaling/README.md`](../infra/kubernetes/scaling/README.md).

## Kafka retry and dead-letter operation

Analysis, Results, and Event Observation use bounded retries for validated records and dedicated dead-letter topics for deterministic poison records or retry exhaustion. The default topics are:

```text
signalharvester.analysis.raw-item-dead-letter.v1
signalharvester.results.analysis-outcome-dead-letter.v1
signalharvester.event-observation.dead-letter.v1
```

Each DLQ value is a versioned `failure/v1/DeadLetterEvent` defined at `contracts/event-contracts/src/main/proto/io/signalharvester/events/failure/v1/dead-letter-event.proto`.

It preserves:

- deterministic dead-letter identity;
- original topic, partition, offset, key, and value;
- consumer identity;
- failure type and message;
- attempt count;
- retryable classification.

Local Redpanda uses topic auto-creation, so these topics appear on the first terminal failure.

Analysis returns normally only after deduplication state and the terminal-event outbox row commit together in PostgreSQL. Kafka terminal-event delivery can therefore recover independently from a broker outage. Micronaut `SYNC_PER_RECORD` performs the synchronous source-offset commit only after that successful listener completion.

Results and Event Observation use the same boundary: durable processing completes first, then Micronaut commits the completed source record.

Failed inputs become eligible for framework offset commit only after acknowledged DLQ publication. If a DLQ producer is unavailable, the listener fails before successful completion and the source record remains recoverable by Kafka redelivery. A framework-level commit failure is outside SignalHarvester application retry/DLQ classification and may also lead to normal at-least-once redelivery.

Automatic/bulk replay remains disabled. After correcting the underlying problem, an ADMIN can inspect and replay one known owner-specific DLQ record by Kafka partition/offset through the backend API:

```text
GET  /api/v1/admin/analysis/dead-letters/{partition}/{offset}
POST /api/v1/admin/analysis/dead-letters/{partition}/{offset}/replay
GET  /api/v1/admin/results/dead-letters/{partition}/{offset}
POST /api/v1/admin/results/dead-letters/{partition}/{offset}/replay
GET  /api/v1/admin/event-observation/dead-letters/{partition}/{offset}
POST /api/v1/admin/event-observation/dead-letters/{partition}/{offset}/replay
```

Inspection returns sanitized metadata and `sourcePayloadBytes`, not the serialized payload. Replay requires JSON `{ "expectedDeadLetterId": "<exact-id-from-inspection>" }`. The backend rereads the real DLQ record, validates consumer/group/topic identity, and reprocesses the stored original key/payload only through the owning module. It does not republish the shared source topic or modify Kafka consumer-group offsets. POST requests remain subject to the normal ADMIN authentication and CSRF policy.

### Analysis outbox inspection

The Analysis outbox is module-owned recovery state, not a public application API. During local diagnosis, pending rows can be inspected directly in PostgreSQL when needed:

```sql
SELECT event_id, topic, event_key, created_at, publication_attempts, lease_expires_at, last_error
FROM analysis.event_outbox
WHERE published_at IS NULL
ORDER BY created_at, event_id;
```

Published rows remain marked with `published_at`.

A Kafka acknowledgement followed by failure to persist that marker can cause the same stored bytes to be published again after lease recovery. Stable event identity and downstream idempotency make this intentional at-least-once behavior.

Start a manual collection run for an existing monitoring-profile UUID:

```bash
curl -i -X POST http://localhost:8080/api/v1/admin/collection-runs \
  -H 'Content-Type: application/json' \
  -d '{"monitoringProfileId":"<MONITORING_PROFILE_UUID>"}'
```

The category and source membership come from the persisted profile and cannot be overridden by this request.

Inspect recent runs:

```bash
curl 'http://localhost:8080/api/v1/admin/collection-runs?limit=20'
```

Inspect recent normalized analysis claims:

```bash
curl 'http://localhost:8080/api/v1/admin/analysis/items?limit=20'
```

The analysis inspection API exposes durable normalization/deduplication provenance only. User-facing analyzed state belongs to Results.


### Live backend pipeline verification

For a separately running backend, use the opt-in black-box verifier instead of copying multiple `curl` commands. It can optionally import a source manifest.

When `--profile` is omitted, the verifier:

1. creates a temporary persisted Monitoring Profile over the enabled sources;
2. starts a manual Collection Run;
3. waits until correlated output becomes visible through the public Results API;
4. removes the temporary profile afterward.

```bash
python3 tools/live-backend/verify_pipeline.py \
  --base-url http://localhost:8080 \
  --manifest path/to/sources.real-trial.json \
  --category GENERAL
```

For a deterministic RSS extraction check with no public-network dependency, run the backend on the host and use the temporary two-entry loopback feed:

```bash
python3 tools/live-backend/verify_pipeline.py \
  --base-url http://localhost:8080 \
  --local-rss-fixture \
  --category GENERAL
```

The live command intentionally does **not** run from `run_checks.sh` because it depends on an already running backend. Only its deterministic self-tests run in the repository gate.

See [`../tools/live-backend/README.md`](../tools/live-backend/README.md).

Browse recent analyzed results:

```bash
curl 'http://localhost:8080/api/v1/results?limit=20&monitoringProfileId=real-trial'
```

Useful optional filters are `sourceId`, `informationCategory`, `relevant`, `classification`, `analyzedFrom`, and `analyzedTo`. `search` performs full-text matching over title and normalized content. The list representation intentionally omits the potentially large normalized content while retaining bounded attributes and tags. When `X-Next-Cursor` is present, pass its value as `cursor` with the same filters/search to continue; changing only `limit` is allowed.

Inspect one detailed profile-scoped result:

```bash
curl 'http://localhost:8080/api/v1/results/<NORMALIZED_ITEM_ID>?monitoringProfileId=real-trial'
```

The detail response includes normalized content, attributes, ordered tags, analysis metadata, and event/correlation provenance. Browser clients must use this REST boundary rather than Results tables directly.

Stream later result changes with SSE:

```bash
curl -N -H 'Accept: text/event-stream' \
  'http://localhost:8080/api/v1/results/stream?monitoringProfileId=real-trial'
```

A fresh Results SSE connection receives `ready` with the current numeric cursor before later updates.

Event meanings are:

- `result` — compact `ResultSummary` payload;
- `keepalive` — keeps an idle connection active.

Browser `EventSource` reconnects with the last SSE `id` as `Last-Event-ID`, so the backend resumes after the last committed cursor.

The stream represents current projections, not append-only event history. Several disconnected updates to one logical result may collapse to the latest projection.

For a race-free browser bootstrap:

1. open SSE and wait for `ready`;
2. load the Results REST feed while buffering later `result` events;
3. apply the REST snapshot;
4. merge buffered/live updates by `(monitoringProfileId, normalizedItemId)`.

Do not load REST first and open SSE afterward. A result committed between those operations could be missed. SSE is incremental delivery, not a duplicate initial snapshot.


## Inspect technical event history

List the newest retained processing events:

```bash
curl 'http://localhost:8080/api/v1/events?limit=100'
```

Useful Event Explorer filters are:

- `eventType`;
- `producer`;
- `topic`;
- `correlationId`;
- `collectionRunId`;
- `itemId`;
- `traceId`.

In the current pipeline, the Collection Run ID is also the event correlation ID. `itemId` matches either raw or normalized item identity.

Stream later technical events with SSE:

```bash
curl -N -H 'Accept: text/event-stream' \
  'http://localhost:8080/api/v1/events/stream?collectionRunId=<COLLECTION_RUN_ID>'
```

A fresh Event Explorer connection receives `ready` before later `event` updates. `keepalive` events keep idle connections active. Browser reconnection uses `Last-Event-ID`.

For a race-free initial load:

1. establish SSE and receive `ready`;
2. load the REST history snapshot;
3. buffer later `event` messages until the snapshot is applied;
4. merge buffered/live events by `eventId`.

Event history is diagnostic and retention-bounded. A cursor older than retained history can recover only records that still exist.

Reconstruct the application-level processing graph for a collection run:

```bash
curl 'http://localhost:8080/api/v1/flows/collection-runs/<COLLECTION_RUN_ID>'
```

Reconstruct one raw or normalized item branch inside that run:

```bash
curl 'http://localhost:8080/api/v1/flows/collection-runs/<COLLECTION_RUN_ID>/items/<ITEM_ID>'
```

Flow nodes state their evidence level:

- `OBSERVED_EVENT` and `OBSERVED_KAFKA_METADATA` — backed directly by retained technical events;
- `DERIVED_FROM_EVENT` — inferred from current published-event semantics;
- `NOT_OBSERVED` — the backend cannot prove the stage from Event Observation data.

Results persistence currently appears as `NOT_OBSERVED`. The graph does not claim completion merely because a terminal Analysis event was published.

## Run tests and repository checks

Run the complete repository verification gate with:

```bash
./run_checks.sh
```

It runs the default Gradle verification, all container-backed integration tests, and a reproducible FULL-archive check. See [`TESTS.md`](TESTS.md) for focused commands and the authoritative validation matrix.

## Planned operational workflow

As the first vertical slice grows, this document will add commands for:

- cron/calendar scheduling and historical missed-interval catch-up;

Do not duplicate UI installation or user-interface instructions here.
