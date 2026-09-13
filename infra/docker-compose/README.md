---
type: Infrastructure Guide
title: Local Docker Compose infrastructure
description: Local PostgreSQL and Redpanda lifecycle, configurable host endpoints, and reset behavior for backend development.
---
# Local development infrastructure

This Docker Compose stack provides the infrastructure required by the host-run SignalHarvester backend:

- PostgreSQL 16 for module-owned persistence;
- a single-node Redpanda broker for the Kafka-compatible local event transport.

Redpanda is used only as the local development broker. The application contract remains Kafka + Protobuf, and integration tests continue to validate the Kafka boundary independently.

## Start with defaults

From the repository root:

```bash
docker compose -f infra/docker-compose/compose.yaml up -d
```

Inspect service health:

```bash
docker compose -f infra/docker-compose/compose.yaml ps
```

Default host endpoints are:

- PostgreSQL: `localhost:5432`;
- Kafka API: `localhost:9092`;
- Redpanda Admin API: `localhost:9644`.

## Local overrides

The Compose file is parameterized. Copy the safe example before changing local ports or credentials:

```bash
cp infra/docker-compose/.env.example infra/docker-compose/.env
```

Start Compose with that file explicitly so behavior does not depend on the caller's working directory or implicit `.env` lookup rules:

```bash
docker compose \
  --env-file infra/docker-compose/.env \
  -f infra/docker-compose/compose.yaml \
  up -d
```

Supported Compose-facing values currently include:

- `SIGNALHARVESTER_DB_NAME`;
- `SIGNALHARVESTER_DB_USERNAME`;
- `SIGNALHARVESTER_DB_PASSWORD`;
- `SIGNALHARVESTER_DB_PORT`;
- `SIGNALHARVESTER_KAFKA_ADVERTISED_HOST`;
- `SIGNALHARVESTER_KAFKA_PORT`;
- `SIGNALHARVESTER_REDPANDA_ADMIN_PORT`.

The same example file also contains the corresponding backend `SIGNALHARVESTER_DB_URL` and `SIGNALHARVESTER_KAFKA_BOOTSTRAP_SERVERS`. When overriding the database name or host ports, keep those application values aligned before sourcing the file for a host-run backend.

For example:

```bash
set -a
. infra/docker-compose/.env
set +a
./gradlew :app:run --no-watch-fs
```

The Redpanda `dev-container` mode enables topic auto-creation for local development, so the current versioned application topics are created on first use. Production environments must provision topics explicitly.

## Stop

With defaults:

```bash
docker compose -f infra/docker-compose/compose.yaml down
```

With an override file, use the same `--env-file` argument used for startup:

```bash
docker compose \
  --env-file infra/docker-compose/.env \
  -f infra/docker-compose/compose.yaml \
  down
```

## Reset local data

```bash
docker compose -f infra/docker-compose/compose.yaml down -v --remove-orphans
```

Use the same explicit `--env-file` option when the stack was started with overrides. This removes both PostgreSQL and Redpanda development data. Do not use the reset command when local data must be preserved.
