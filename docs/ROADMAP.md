---
type: Roadmap
title: Roadmap
description: Compact SignalHarvester backend roadmap and current implementation direction.
---
# Roadmap

## Current implementation focus

The first real-source vertical slice including Results REST/SSE is verified. The current backend focus is bounded event observation under [`specs/active/subspecs/backend-event-observation.md`](specs/active/subspecs/backend-event-observation.md). The following slice is processing-flow correlation/reconstruction for the Event Explorer.

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

- event-observation pipeline and bounded correlation/event history;
- backend support for UI Event Explorer live/history views;
- processing-flow reconstruction.

## P2 — deployment and observability

- Kubernetes deployment;
- OpenTelemetry, Prometheus, Loki, Tempo, Grafana;
- failure/retry/DLQ/idempotency demonstrations.

## Deferred until justified

- independently deployed backend microservices;
- gRPC service boundaries;
- Schema Registry;
- LLM/embedding analysis as a required core dependency.


The backend now provides source CRUD/testing, manual and scheduled collection, durable run inspection, analysis inspection, Results REST/SSE, and technical Event Explorer history/SSE. The next backend milestone is processing-flow reconstruction; the companion UI can consume the existing contracts independently.
