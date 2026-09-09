---
type: Usage Guide
title: Usage
description: Current backend run and developer workflows for SignalHarvester.
---
# Usage

## Scope

This document owns backend operational workflows and commands. UI-specific workflows belong to the separate `signalharvester-ui` project.

## Run the current backend foundation

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

The current application has no product REST endpoint yet. A successful start validates the Micronaut composition root only.

## Run tests

See [`TESTS.md`](TESTS.md) for the authoritative test command matrix.

## Planned operational workflow

As the first vertical slice grows, this document will add commands for:

- local PostgreSQL/Kafka infrastructure;
- Flyway migration validation;
- REST/OpenAPI exercises;
- SSE streams;
- deterministic collection scenarios;
- event-flow inspection.

Do not duplicate UI installation or user-interface instructions here.
