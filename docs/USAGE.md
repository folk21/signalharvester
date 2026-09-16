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

Flyway applies the configuration, analysis, collection, Results, Event Observation, and Security migrations at startup. The backend exposes source CRUD and diagnostic source testing under `/api/v1/sources`, monitoring-profile CRUD under `/api/v1/monitoring-profiles`, operational collection-run endpoints under `/api/v1/admin/collection-runs`, and analysis inspection under `/api/v1/admin/analysis/items`. The analysis Kafka listener starts by default and waits for `RawItemDiscovered` events. The Results listener also starts by default and materializes terminal `ItemAnalyzed` / `ItemRejected` events into PostgreSQL.

### Run with authentication and RBAC

The default local profile remains the existing trusted-environment mode while the frontend authentication work is pending. Do not expose that mode publicly. To exercise the protected backend boundary, activate the `security` environment and provide deployment-owned secrets:

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

A source may remain `enabled=false` while it is being tested. Fetch and extraction problems are returned in the diagnostic JSON payload with `FETCH_FAILED` or `EXTRACTION_FAILED`; the endpoint itself returns HTTP 200 when the persisted source exists. See [`CONFIGURATION.md`](CONFIGURATION.md) for `json.*` and `html.*` extraction settings.

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

Source URLs stored through this API are configuration data only. Runtime collection applies the accepted outbound destination policy described in [`CONFIGURATION.md`](CONFIGURATION.md). The default trusted-local environment intentionally permits loopback/private fixtures. The `security` environment uses `SECURE` mode and rejects loopback/private/carrier-grade-NAT/link-local destinations unless the operator explicitly allows the required CIDR.

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

The importer matches by source type plus normalized location, skips existing identities, and does not reconcile changed names/settings/enabled flags. Read [`../tools/source-import/README.md`](../tools/source-import/README.md) for manifest versioning, normalization, failure semantics, and security constraints.

The collection run workflow resolves one persisted monitoring profile through the configuration API, preserves its configured source order, skips disabled member sources, performs bounded best-effort fetches, extracts semantic items, persists the completed run snapshot, and publishes successful items to Kafka. Enabled profiles are also scheduled automatically from their persisted collection interval using collection-owned PostgreSQL leases. RSS/Atom produce one item per feed entry up to the configured bound. REST sources may extract candidate objects through persisted `json.*` JSON Pointer settings, and HTML sources may extract candidate elements through persisted `html.*` CSS selector settings. REST/HTML sources without those settings still produce one passthrough item per response. The analysis listener consumes those raw events, normalizes/deduplicates them, runs deterministic keyword analysis, and atomically stages `ItemAnalyzed` or `ItemRejected` bytes in the Analysis PostgreSQL outbox. The outbox dispatcher publishes those committed records to Kafka. Results consumes the terminal events, persists an idempotent Results-owned projection, and exposes analyzed results through REST plus resumable SSE live delivery.

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

Application metrics include Collection Run/source-fetch outcomes and durations plus Analysis processing/outbox publication outcomes. Their labels are intentionally low-cardinality. Event Explorer and Processing Flow remain the better tools for inspecting one concrete run/item/event.

Trace export is disabled by default. When an OTLP collector is available, enable it for the backend process:

```bash
SIGNALHARVESTER_OTEL_TRACES_EXPORTER=otlp \
SIGNALHARVESTER_OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 \
./gradlew :app:run --no-watch-fs
```

Console logs include `trace_id` and `span_id` fields when a valid OpenTelemetry span is active. The backend still writes application logs to stdout/stderr; use shell redirection or the deployment logging stack for file/storage collection.

## Run the production-style local Kubernetes stack

The backend-owned Kubernetes deployment is currently verification-pending. Build/load `signalharvester-backend:local`, create local runtime secrets, apply the Kustomize target, and run the live cluster verification:

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

Grafana is provisioned with Prometheus, Loki, and Tempo plus the **SignalHarvester Overview** dashboard. Application Event Explorer/Processing Flow remain the correct place for one concrete run or item; Grafana is for aggregate runtime and infrastructure behavior. Full Kubernetes platform acceptance additionally requires a real `signalharvester-web` image using the separate `infra/kubernetes/frontend` workload boundary.

### Run controlled resilience acceptance

After the backend/infrastructure stack is healthy, run the opt-in live resilience harness:

```bash
python3 infra/kubernetes/resilience/run_acceptance.py
```

The harness deploys a temporary in-cluster RSS fixture and exercises backend container restart, slow-source availability, PostgreSQL outage with bounded Analysis retry/DLQ, Kafka lag and recovery, Analysis outbox recovery, scheduler leases across two replicas, Redpanda restart recovery, authorization boundaries, and Prometheus/Loki/Tempo evidence. It restores temporary backend environment overrides and removes fixture/profile/source resources on exit. See [`../infra/kubernetes/resilience/README.md`](../infra/kubernetes/resilience/README.md) for the fault-injection boundaries and options.

## Kafka retry and dead-letter operation

Analysis, Results, and Event Observation use bounded retries for validated records and dedicated dead-letter topics for deterministic poison records or retry exhaustion. The default topics are:

```text
signalharvester.analysis.raw-item-dead-letter.v1
signalharvester.results.analysis-outcome-dead-letter.v1
signalharvester.event-observation.dead-letter.v1
```

Each DLQ value is a versioned `failure/v1/DeadLetterEvent` defined at `contracts/event-contracts/src/main/proto/io/signalharvester/events/failure/v1/dead-letter-event.proto`. It preserves a deterministic dead-letter identity, the original topic/partition/offset/key/value, consumer identity, failure type/message, attempt count, and retryable classification. Local Redpanda runs with topic auto-creation, so these topics appear on first terminal failure.

For Analysis, a normal source offset advances after the deduplication change and terminal-event outbox row commit together in PostgreSQL; Kafka delivery can therefore recover independently from a broker outage. Results and Event Observation advance after their normal durable processing. Failed inputs still advance only after acknowledged DLQ publication. If a DLQ producer is unavailable, the source record remains uncommitted and remains recoverable by Kafka redelivery rather than being silently skipped. There is intentionally no automatic replay command yet; correct the underlying problem before performing any controlled replay with Kafka tooling.

### Analysis outbox inspection

The Analysis outbox is module-owned recovery state, not a public application API. During local diagnosis, pending rows can be inspected directly in PostgreSQL when needed:

```sql
SELECT event_id, topic, event_key, created_at, publication_attempts, lease_expires_at, last_error
FROM analysis.event_outbox
WHERE published_at IS NULL
ORDER BY created_at, event_id;
```

Published rows remain marked with `published_at`. A Kafka acknowledgement followed by a failure to persist that marker can cause the same stored bytes to be published again after lease recovery. The stable event id and existing downstream idempotency make that replay intentional at-least-once behavior.

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

For a separately running backend, use the opt-in black-box verifier instead of manually copying multiple `curl` commands. It can optionally import a source manifest. When `--profile` is omitted it creates a temporary persisted monitoring profile over the enabled sources, starts a manual collection run, waits until correlated output becomes visible through the public Results API, and removes the temporary profile afterward:

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

The live command intentionally does **not** run from `run_checks.sh`: it depends on an already running backend. Only its deterministic self-tests run in the repository gate. See [`../tools/live-backend/README.md`](../tools/live-backend/README.md).

Browse recent analyzed results:

```bash
curl 'http://localhost:8080/api/v1/results?limit=20&monitoringProfileId=real-trial'
```

Useful optional filters are `sourceId`, `informationCategory`, `relevant`, `classification`, `analyzedFrom`, and `analyzedTo`. The list representation intentionally omits the potentially large normalized content and attribute map.

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

A fresh connection receives a named `ready` event with the current numeric cursor and then only later updates. Named `result` events carry the compact `ResultSummary` payload. `keepalive` events keep idle connections active. Browser `EventSource` automatically reconnects with the last SSE `id` as `Last-Event-ID`, so the backend resumes after the last committed cursor. The stream represents current projections rather than an append-only event history; several updates to one logical result while disconnected may collapse to the latest projection.

For a race-free browser bootstrap, open SSE first and wait for `ready`. Then load the regular Results REST feed while buffering later `result` events. Apply the REST snapshot and then merge the buffered/live updates by `(monitoringProfileId, normalizedItemId)`. Do not load REST first and open SSE afterward, because a result committed between those operations could be missed. SSE is incremental delivery, not a duplicate initial snapshot.


## Inspect technical event history

List the newest retained processing events:

```bash
curl 'http://localhost:8080/api/v1/events?limit=100'
```

Useful Event Explorer filters are `eventType`, `producer`, `topic`, `correlationId`, `collectionRunId`, `itemId`, and `traceId`. In the current pipeline the collection run id is also the event correlation id. `itemId` matches either raw or normalized item identity.

Stream later technical events with SSE:

```bash
curl -N -H 'Accept: text/event-stream' \
  'http://localhost:8080/api/v1/events/stream?collectionRunId=<COLLECTION_RUN_ID>'
```

A fresh connection receives `ready` and then later `event` updates. `keepalive` events keep idle connections active. Browser reconnection uses `Last-Event-ID`. For a race-free initial Event Explorer load, establish SSE and receive `ready` before loading the REST history snapshot, buffer later `event` messages until that snapshot is applied, and then merge the buffered/live events by `eventId`. Event history is diagnostic and retention-bounded; a cursor older than retained history can recover only events that still exist.

Reconstruct the application-level processing graph for a collection run:

```bash
curl 'http://localhost:8080/api/v1/flows/collection-runs/<COLLECTION_RUN_ID>'
```

Reconstruct one raw or normalized item branch inside that run:

```bash
curl 'http://localhost:8080/api/v1/flows/collection-runs/<COLLECTION_RUN_ID>/items/<ITEM_ID>'
```

Flow nodes state their evidence level. `OBSERVED_EVENT` and `OBSERVED_KAFKA_METADATA` are backed directly by retained technical events. `DERIVED_FROM_EVENT` is inferred from the current published event semantics. `NOT_OBSERVED` means the backend intentionally cannot prove that stage from Event Observation data. Results persistence currently appears as `NOT_OBSERVED`; the graph does not claim completion merely because a terminal Analysis event was published.

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
