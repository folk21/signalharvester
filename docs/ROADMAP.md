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

## P2 — reliability, observability, and security

- bounded retry, poison-event handling, DLQ/failure inspection, and idempotency hardening;
- PostgreSQL/Kafka consistency through transactional outbox or equivalent;
- OpenTelemetry application instrumentation and health/readiness;
- stateless JWT authentication with persisted additive roles and backend-enforced RBAC;
- explicit `VIEWER` result access versus `ADMIN` operational/diagnostic access.

## P2 — deployment and system acceptance

- Kubernetes deployment;
- Prometheus, Loki, Tempo, Grafana infrastructure observability;
- controlled failure/restart/lag and authorization-boundary demonstrations;
- one production-style local system acceptance pass before lower-priority product refinements.

## Deferred until justified

- independently deployed backend microservices;
- gRPC service boundaries;
- Schema Registry;
- LLM/embedding analysis as a required core dependency.


The backend now provides source CRUD/testing, manual and scheduled collection, durable run inspection, analysis inspection, Results REST/SSE, technical Event Explorer history/SSE, and processing-flow reconstruction. The next backend milestones are reliability/consistency, application observability, and authentication/RBAC before production-style Kubernetes/system acceptance. When frontend work resumes, the companion frontend specification should add a dedicated consumer-facing `VIEWER` result experience over the accepted backend security and Results contracts.
