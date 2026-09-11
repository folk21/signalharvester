# Local development infrastructure

This Docker Compose stack provides the infrastructure required by the host-run SignalHarvester backend:

- PostgreSQL 16 for module-owned persistence;
- a single-node Redpanda broker for the Kafka-compatible local event transport.

Redpanda is used only as the local development broker. The application contract remains Kafka + Protobuf, and integration tests continue to validate the Kafka boundary independently.

## Start

From the repository root:

```bash
docker compose -f infra/docker-compose/compose.yaml up -d
```

Inspect service health:

```bash
docker compose -f infra/docker-compose/compose.yaml ps
```

Expected host endpoints:

- PostgreSQL: `localhost:5432`;
- Kafka API: `localhost:9092`;
- Redpanda Admin API: `localhost:9644`.

The Redpanda `dev-container` mode enables topic auto-creation for local development, so the current versioned application topics are created on first use. Production environments must provision topics explicitly.

## Stop

```bash
docker compose -f infra/docker-compose/compose.yaml down
```

## Reset local data

```bash
docker compose -f infra/docker-compose/compose.yaml down -v --remove-orphans
```

This removes both PostgreSQL and Redpanda development data. Do not use the reset command when local data must be preserved.
