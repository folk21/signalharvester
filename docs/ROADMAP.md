---
type: Roadmap
title: Roadmap
description: Compact SignalHarvester backend roadmap and current implementation direction.
---
# Roadmap

## Current implementation focus

Complete the first useful real-source vertical slice. Results persistence/REST, RSS/Atom extraction, and persisted monitoring-profile configuration are verified. The current backend focus is profile-driven scheduling under [`specs/active/subspecs/backend-profile-driven-scheduling.md`](specs/active/subspecs/backend-profile-driven-scheduling.md). The next backend slice is source-test/generic extraction before Results live delivery and event observation.

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


The backend operational/admin APIs now provide source CRUD, manual collection execution, durable run inspection, analysis inspection, and Results reads. The next UI milestone is a real Admin UI over these existing contracts; result SSE and Event Explorer remain subsequent vertical slices.
