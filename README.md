---
type: Project Overview
title: SignalHarvester
description: Entry point for the modular event-driven backend, its architecture, contracts, workflows, and documentation.
---
# SignalHarvester

SignalHarvester is a modular event-driven backend for collecting, analyzing, and presenting information from configurable external sources.

The first product focus is monitoring job vacancies and selected information topics. The backend is intentionally designed as a **modular monolith first**: one deployable Micronaut application composed from cohesive Gradle modules with explicit Java and event contracts. Kafka, PostgreSQL, Kubernetes, and observability remain first-class parts of the system without forcing every module to become a microservice.

## System at a glance

```mermaid
flowchart TB
    EXT[External sources] --> COL[Collection module]
    COL --> K[Kafka / Protobuf events]
    K --> ANA[Analysis module]
    ANA --> RES[Results module]
    RES --> DB[(PostgreSQL)]
    API[Backend REST + SSE] --> UI[SignalHarvester UI]
    DB --> API
    K --> OBS[Event observation]
    OBS --> API
```

Kafka events are defined by versioned `.proto` files. In-process synchronous module collaboration uses Java interfaces/types. Browser and test clients use REST/JSON, and browser live updates use SSE/JSON.

## Companion UI project

The web frontend is a separate repository. The frontend repository is **`signalharvester-web`** and owns the complete SignalHarvester Web application. The current operational/admin screens are its first implemented product slice, not a separate admin-only frontend.

This backend repository owns the public REST/OpenAPI and SSE contracts consumed by the UI, but it does not own frontend implementation details.

For UI installation and run instructions, use the **UI repository's root `README.md`** as the initial authoritative guide. The UI project should have its own `docs/specs/` tree from the beginning. Separate UI `docs/INSTALLATION.md` and `docs/USAGE.md` files should be introduced only when setup/operation becomes too large or independently owned for a single README.

Backend [`docs/INSTALLATION.md`](docs/INSTALLATION.md) and [`docs/USAGE.md`](docs/USAGE.md) describe backend/infrastructure setup and operation only.

## Start here

| Goal | Read |
|---|---|
| Repository development rules | [`AGENTS.md`](AGENTS.md) |
| Product/system target and active work | [`docs/specs/README.md`](docs/specs/README.md) |
| Stable feature vocabulary | [`docs/FEATURES.md`](docs/FEATURES.md) |
| Stable architecture boundaries | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| Current implementation state | [`docs/IMPLEMENTATION.md`](docs/IMPLEMENTATION.md) |
| Backend setup prerequisites | [`docs/INSTALLATION.md`](docs/INSTALLATION.md) |
| Backend usage/run workflows | [`docs/USAGE.md`](docs/USAGE.md) |
| Configuration ownership | [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md) |
| Tests and validation | [`docs/TESTS.md`](docs/TESTS.md) |
| Coverage and code quality | [`docs/QUALITY.md`](docs/QUALITY.md) |
| Product/technical roadmap | [`docs/ROADMAP.md`](docs/ROADMAP.md) |

## Repository structure

```text
signalharvester/
├── app/                         # Micronaut composition root
├── common/                      # Small stable shared primitives/utilities
├── contracts/
│   ├── api-contracts/           # OpenAPI/REST contract sources
│   └── event-contracts/         # Kafka Protobuf contract sources
├── modules/
│   ├── configuration/
│   ├── collection/
│   ├── analysis/
│   ├── results/
│   ├── event-observation/
│   └── security/
├── testing/
│   ├── test-support/
│   └── integration-tests/
├── infra/
│   ├── docker-compose/
│   ├── kubernetes/
│   └── observability/
└── docs/
    └── specs/
```

Read [`modules/README.md`](modules/README.md) for functional-module ownership and [`contracts/README.md`](contracts/README.md) for external/event contract ownership.

## Current state

The first implementation foundation is present:

- a runnable Micronaut composition root in `app`;
- centralized dependency/plugin versions through `gradle/libs.versions.toml`, with the Micronaut Platform version exposed as the Gradle plugin-compatible `micronautVersion` property;
- the configuration-module Java API plus PostgreSQL/Flyway-backed source and monitoring-profile persistence;
- the source REST/OpenAPI CRUD contract implemented under `/api/v1/sources`;
- versioned Protobuf `EventEnvelope`, `RawItemDiscovered`, `ItemAnalyzed`, and `ItemRejected` Kafka schemas;
- JUnit contract tests plus PostgreSQL and Kafka Testcontainers coverage for the implemented persistence/event boundaries;
- repository-level JaCoCo coverage, SpotBugs static-analysis, and dependency-health reporting integrated into the canonical verification workflow;
- the first collection HTTP transport using Micronaut-managed HTTP infrastructure with bounded Virtual Thread orchestration and deterministic loopback tests;
- collection-owned source extraction with bounded RSS/Atom parsing, configuration-driven REST/JSON and HTML extraction, and backward-compatible passthrough when generic extraction is not configured;
- diagnostic persisted-source testing through the normal fetch/extraction boundary without publishing test data into Kafka or collection-run history;
- profile-driven collection execution over persisted monitoring-profile source membership, with bounded best-effort fetch, deterministic raw-item identity, run correlation, and partial-failure results;
- collection-owned PostgreSQL interval scheduling with cluster-safe leases and lease heartbeats across backend replicas;
- the Analysis consumer pipeline with deterministic normalization, PostgreSQL-backed profile-scoped deduplication, configurable keyword analysis, manual source-offset commit after durable processing, and transactional-outbox staging of `ItemAnalyzed`/`ItemRejected`;
- a first operational admin API for manual collection runs, durable run/source outcome history, and read-only normalized-item inspection;
- Results-owned PostgreSQL materialization of `ItemAnalyzed` and `ItemRejected` with idempotent at-least-once Kafka consumption;
- a bounded public Results REST API for recent feed browsing and profile-scoped result detail;
- Results-owned resumable SSE live delivery backed by durable PostgreSQL cursors and browser `Last-Event-ID` reconnection;
- event-observation persistence over published raw/analyzed/rejected Kafka events, with bounded diagnostic history plus REST/SSE Event Explorer APIs;
- bounded collection-run and item processing-flow reconstruction with explicit observed, derived, and unobserved stage evidence;
- bounded Kafka consumer retry and versioned dead-letter handling for Analysis, Results, and Event Observation poison/failure paths;
- application observability through health/readiness endpoints, Prometheus metrics, OpenTelemetry HTTP/Kafka/JDBC tracing, trace-correlated console logs, and preserved trace context across Collection fan-out and the Analysis outbox.
- verification-pending backend security with persisted application identities, additive USER/VIEWER/ADMIN/BOT roles, stateless JWT authentication, cookie/CSRF browser transport, and backend-enforced RBAC.

Kubernetes deployment, the production observability stack, and the consumer-facing VIEWER frontend remain planned work. Backend authentication/authorization is implemented and verification-pending; the default local profile remains the explicitly trusted unauthenticated compatibility mode until the frontend is migrated. The Analysis authoritative-state/Kafka consistency gap is addressed by the accepted transactional outbox. Repository-owned Docker Compose provides local PostgreSQL and Kafka infrastructure.

See [`docs/IMPLEMENTATION.md`](docs/IMPLEMENTATION.md) for the exact implemented state and [`docs/USAGE.md`](docs/USAGE.md) for current runnable commands.

For local development infrastructure, defaults work without an env file:

```bash
docker compose -f infra/docker-compose/compose.yaml up -d
```

For local overrides, copy `infra/docker-compose/.env.example` to `infra/docker-compose/.env` and pass it explicitly with `--env-file`. See [`infra/docker-compose/README.md`](infra/docker-compose/README.md).

## Documentation model

Documentation uses minimal YAML frontmatter (`type`, `title`, `description`) to make purpose searchable for humans and LLMs. Active specifications add relationship/workflow metadata defined by [`docs/specs/README.md`](docs/specs/README.md).

Stable capability names live in [`docs/FEATURES.md`](docs/FEATURES.md). Feature IDs are long-lived cross-references; requirement IDs remain local to their specifications, and completed implementation specs are archived.

Current-state documentation and active specs have different roles: specs define intended changes; architecture/implementation/configuration/usage docs describe the accepted current system.

## License

See [`LICENSE.md`](LICENSE.md). No redistribution license is currently assumed unless that file is updated explicitly.
