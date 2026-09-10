---
type: Usage Guide
title: Usage
description: Current backend run and developer workflows for SignalHarvester.
---
# Usage

## Scope

This document owns backend operational workflows and commands. UI-specific workflows belong to the separate `signalharvester-ui` project.

## Run the backend

```bash
./gradlew :app:run
```

If archive extraction did not preserve executable permission:

```bash
bash ./gradlew :app:run
```

The current server port defaults to `8080` and can be overridden:

```bash
SIGNALHARVESTER_HTTP_PORT=8081 ./gradlew :app:run
```

Flyway applies the configuration-module migration at startup. The backend currently exposes source configuration CRUD under `/api/v1/sources`.

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

## Run tests

See [`TESTS.md`](TESTS.md) for the authoritative test command matrix.

## Planned operational workflow

As the first vertical slice grows, this document will add commands for:

- local Kafka infrastructure;
- SSE streams;
- deterministic collection scenarios;
- event-flow inspection.

Do not duplicate UI installation or user-interface instructions here.
