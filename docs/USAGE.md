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

Flyway applies the configuration, analysis, and collection operational-history migrations at startup. The backend exposes source configuration CRUD under `/api/v1/sources`, operational collection-run endpoints under `/api/v1/admin/collection-runs`, and analysis inspection under `/api/v1/admin/analysis/items`. The analysis Kafka listener starts by default and waits for `RawItemDiscovered` events.

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

Source URLs stored through this API are configuration data only. Do not expose source management to untrusted users as an unrestricted collection authorization mechanism until an outbound destination/SSRF policy is implemented.

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

The collection run workflow reads enabled sources through the configuration module API, performs bounded best-effort fetches, persists the completed run snapshot, and publishes successful payloads to Kafka. The analysis listener consumes those raw events, normalizes/deduplicates them, runs deterministic keyword analysis, and publishes `ItemAnalyzed` or `ItemRejected`. Results are not persisted or exposed yet.

Start a manual collection run:

```bash
curl -i -X POST http://localhost:8080/api/v1/admin/collection-runs \
  -H 'Content-Type: application/json' \
  -d '{"monitoringProfileId":"manual-admin","informationCategory":"JOB"}'
```

Inspect recent runs:

```bash
curl 'http://localhost:8080/api/v1/admin/collection-runs?limit=20'
```

Inspect recent normalized analysis claims:

```bash
curl 'http://localhost:8080/api/v1/admin/analysis/items?limit=20'
```

The analysis inspection API exposes durable normalization/deduplication provenance only. Classification, score, and user-facing results are not yet persisted and therefore are intentionally absent from this API.

## Run tests and repository checks

Run the complete repository verification gate with:

```bash
./run_checks.sh
```

It runs the default Gradle verification, all container-backed integration tests, and a reproducible FULL-archive check. See [`TESTS.md`](TESTS.md) for focused commands and the authoritative validation matrix.

## Planned operational workflow

As the first vertical slice grows, this document will add commands for:

- monitoring-profile scheduling;
- result REST/SSE streams;
- event-flow inspection.

Do not duplicate UI installation or user-interface instructions here.
