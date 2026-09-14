---
type: Roadmap
title: Roadmap
description: Compact SignalHarvester backend roadmap and current implementation direction.
---
# Roadmap

## Current implementation focus

Complete the first useful real-source vertical slice. Results persistence/REST, RSS/Atom extraction, persisted monitoring-profile configuration, profile-driven cluster-safe scheduling, source testing, and generic REST/JSON/HTML extraction are verified. The current backend focus is Results SSE/live delivery under [`specs/active/subspecs/backend-results-sse-live-delivery.md`](specs/active/subspecs/backend-results-sse-live-delivery.md). The next backend slice is event observation.

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


The backend operational/admin APIs now provide source CRUD/testing, manual and scheduled collection, durable run inspection, analysis inspection, Results reads, and live Results SSE. The next backend milestone is event observation for the Event Explorer; the companion UI can consume the existing REST/SSE contracts independently.
