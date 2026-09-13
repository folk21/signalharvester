---
type: Roadmap
title: Roadmap
description: Compact SignalHarvester backend roadmap and current implementation direction.
---
# Roadmap

## Current implementation focus

Complete the first Results vertical slice. Terminal Analysis events are now materialized into Results-owned PostgreSQL state under [`specs/active/subspecs/backend-results-persistence.md`](specs/active/subspecs/backend-results-persistence.md); the next step is the first result read REST API, followed by source extraction needed for a meaningful real-source trial.

## P0 — repository and contract foundation

- stabilize modular-monolith ownership and documentation;
- establish Gradle/Micronaut runnable composition root;
- define first OpenAPI and Protobuf contracts;
- establish PostgreSQL/Flyway ownership and integration-test baseline.

## P1 — first functional vertical slice

- persisted source/profile configuration;
- scheduled collection from a deterministic and at least one real supported source type;
- Kafka/Protobuf event flow;
- normalization/deduplication/basic analysis;
- persisted results exposed through REST and SSE.

## P1 — event visibility

- event-observation pipeline;
- correlation/event history;
- backend support for UI Event Explorer and flow reconstruction.

## P2 — deployment and observability

- Kubernetes deployment;
- OpenTelemetry, Prometheus, Loki, Tempo, Grafana;
- failure/retry/DLQ/idempotency demonstrations.

## Deferred until justified

- independently deployed backend microservices;
- gRPC service boundaries;
- Schema Registry;
- LLM/embedding analysis as a required core dependency.


The first operational admin API now provides manual collection execution, durable completed-run inspection, and read-only analysis deduplication inspection. Result read REST/SSE and Event Explorer remain subsequent vertical slices.
