---
type: Roadmap
title: Roadmap
description: Compact SignalHarvester backend roadmap and current implementation direction.
---
# Roadmap

## Current implementation focus

The first real-source vertical slice, Results REST/SSE, and bounded Event Observation are verified. The current backend focus is processing-flow reconstruction under [`specs/active/subspecs/backend-processing-flow-reconstruction.md`](specs/active/subspecs/backend-processing-flow-reconstruction.md). After this slice, the companion UI can consume the complete Event Explorer history/live/graph backend surface in one frontend pass.

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


The backend now provides source CRUD/testing, manual and scheduled collection, durable run inspection, analysis inspection, Results REST/SSE, technical Event Explorer history/SSE, and processing-flow reconstruction. The next product milestone is a frontend pass over these accepted backend contracts before deeper deployment/observability work.
