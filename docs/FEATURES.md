---
type: Feature Catalog
title: SignalHarvester feature vocabulary
description: Stable human-readable feature identifiers used for cross-references across specifications, documentation, tests, and key implementation entry points.
---
# SignalHarvester feature vocabulary

## Purpose

This document owns the stable feature identifiers used across SignalHarvester documentation and code references.

A feature ID names a durable system capability. It is not a requirement ID and it is not a specification lifecycle ID.

Use the three concepts separately:

| Concept | Purpose | Example | Lifetime |
|---|---|---|---|
| Feature ID | Names a stable capability | `ANALYSIS.OUTBOX` | Long-lived |
| Requirement ID | Names one normative requirement inside a specification | `R22`, `A5` | Lives with that specification |
| Specification | Describes one planned or in-progress change | `backend-authentication-authorization` | Active only while the change is unresolved |

A feature may be implemented or refined by several specifications over time. Archiving a specification does not retire the feature ID.

## Identifier format

Feature IDs use uppercase ASCII words.

- Use `.` between capability levels: `RESULTS.LIVE`.
- Use `_` inside a multi-word level: `DIAGNOSTICS.EVENT_OBSERVATION`.
- Prefer two levels. Add a third level only when it improves navigation.
- Do not encode dates, versions, requirement numbers, implementation stages, Java packages, or deployment topology in the ID.
- Once an ID is referenced outside this catalog, keep it stable. Rename it only as an explicit repository-wide migration.

The dot is a naming hierarchy, not an authorization or inheritance rule. For example, `SECURITY.AUTHORIZATION` does not automatically inherit semantics from another security feature.

## Usage rules

Use feature IDs when they improve navigation or make ownership clear.

Good places include:

- umbrella and sub-spec feature-scope sections;
- requirement cross-references;
- owning architecture or implementation documentation;
- important application entry points and integration boundaries;
- feature-level tests.

Do not add a feature tag to every helper, DTO, mapper, or private implementation class. Avoid metadata noise.

When a Java type or feature-level test benefits from a reference, use ordinary Javadoc:

```java
/**
 * Dispatches committed Analysis outbox records to Kafka.
 *
 * <p>Feature: {@code ANALYSIS.OUTBOX}.</p>
 */
```

Tests and documents must reference IDs from this catalog. Do not invent local feature codes.

## Catalog

### Configuration and collection

| Feature ID | Capability | Primary owner | Related umbrella requirements |
|---|---|---|---|
| `CONFIGURATION.MONITORING_PROFILES` | Persisted monitoring profiles, source membership, criteria, and collection interval | Configuration | R1 |
| `CONFIGURATION.SOURCES` | Persisted external-source configuration | Configuration | R2 |
| `COLLECTION.SOURCE_TEST` | Diagnostic persisted-source fetch/extraction preview | Collection | R3 |
| `COLLECTION.SCHEDULING` | Cluster-safe scheduled collection for enabled profiles | Collection | R4 |
| `COLLECTION.RUNS` | Explicit manual/scheduled collection-run identity and outcomes | Collection | R5 |
| `COLLECTION.ADAPTERS` | Replaceable external-source collection and extraction adapters | Collection | R6 |

### Eventing and analysis

| Feature ID | Capability | Primary owner | Related umbrella requirements |
|---|---|---|---|
| `EVENTING.PIPELINE` | Kafka-backed asynchronous processing between major stages | Owning producer/consumer modules | R7 |
| `EVENTING.CORRELATION` | Stable event, run, item, and trace correlation | Event contracts and owning modules | R8 |
| `ANALYSIS.NORMALIZATION` | Conversion of collected records into normalized content | Analysis | R9 |
| `ANALYSIS.DEDUPLICATION` | Deterministic profile-scoped duplicate handling | Analysis | R10 |
| `ANALYSIS.CLASSIFICATION` | Replaceable deterministic or future provider-backed analysis | Analysis | R11 |
| `ANALYSIS.OUTBOX` | Transactional staging and at-least-once publication of terminal Analysis events | Analysis | R22 |

### Results and presentation

| Feature ID | Capability | Primary owner | Related umbrella requirements |
|---|---|---|---|
| `RESULTS.MATERIALIZATION` | Results-owned durable analyzed-result projection | Results | R12 |
| `RESULTS.LIVE` | Resumable browser live-result delivery over SSE | Results | R13 |
| `PRESENTATION.CONFIGURATION` | Browser configuration workflows over backend contracts | `signalharvester-web` | R14 |
| `RESULTS.BROWSING` | Browser result list/detail inspection over Results APIs | Results + `signalharvester-web` | R15 |
| `PRESENTATION.VIEWER_RESULTS` | Consumer-facing result experience without internal operational detail | `signalharvester-web` | R32 |

### Diagnostics and observability

| Feature ID | Capability | Primary owner | Related umbrella requirements |
|---|---|---|---|
| `DIAGNOSTICS.EVENT_OBSERVATION` | Bounded technical event history and live observation | Event Observation | R16, R28 |
| `DIAGNOSTICS.PROCESSING_FLOW` | Run/item processing-flow reconstruction | Event Observation | R17 |
| `OBSERVABILITY.APPLICATION` | Metrics, structured logs, distributed traces, and health/readiness | App + owning modules | R18 |
| `OBSERVABILITY.INFRASTRUCTURE` | Infrastructure telemetry and Grafana-oriented operational views | Infrastructure | R19 |

### Reliability and runtime

| Feature ID | Capability | Primary owner | Related umbrella requirements |
|---|---|---|---|
| `RELIABILITY.KAFKA_RETRY` | Bounded retry for asynchronous Kafka processing failures | Owning consumers | R20 |
| `RELIABILITY.DEAD_LETTER` | Terminal dead-letter handling for poison/failing Kafka records | Owning consumers + event contracts | R20 |
| `RELIABILITY.IDEMPOTENCY` | Duplicate-safe consumer and persistence behavior | Owning consumers/modules | R21 |
| `RUNTIME.CONCURRENCY` | Explicit blocking/streaming execution and bounded external I/O concurrency | App + owning modules | R23 |
| `SCALABILITY.KAFKA_CONSUMERS` | Horizontal Kafka consumer scaling where partitioning permits | Owning consumers + deployment | R26 |

### Security

| Feature ID | Capability | Primary owner | Related umbrella requirements |
|---|---|---|---|
| `SECURITY.EXTERNAL_SOURCE_ACCESS` | Safe outbound source access, secret separation, and access-policy constraints | Configuration + Collection | R23, R30 |
| `SECURITY.IDENTITY_ROLES` | Persisted identities and explicit additive role assignments | Authentication/authorization capability | R31 |
| `SECURITY.AUTHENTICATION` | Stateless signed-JWT request authentication | Authentication/authorization capability | R31 |
| `SECURITY.AUTHORIZATION` | Backend-enforced role/capability access control | Authentication/authorization capability | R31, R32 |

### Platform, delivery, and testing

| Feature ID | Capability | Primary owner | Related umbrella requirements |
|---|---|---|---|
| `PLATFORM.MODULAR_MONOLITH` | One deployable backend with explicit functional-module boundaries | Repository architecture | R25 |
| `CONTRACTS.KAFKA_PROTOBUF` | Versioned Protobuf Kafka integration contracts | Event contracts | R7, R8 |
| `CONTRACTS.HTTP` | Explicit REST/OpenAPI and SSE/JSON browser contracts | API contracts + owning modules | R25 |
| `DEPLOYMENT.KUBERNETES` | Local production-style Kubernetes deployment | Infrastructure | R24 |
| `DELIVERY.FRONTEND_BACKEND_BOUNDARY` | Independently buildable frontend/backend repositories joined by explicit contracts | Backend + `signalharvester-web` | R25 |
| `DATA.PROVENANCE` | Retained source/run provenance for collected results | Collection, Analysis, Results | R27 |
| `TESTING.DETERMINISTIC_LOCAL` | Deterministic tests without dependence on public Internet services | Repository testing | R29 |

## Adding a feature ID

Add an ID only when the capability is expected to remain meaningful beyond one implementation patch or specification.

Before adding one:

1. check this catalog for an existing capability with the same meaning;
2. choose the owning area rather than the current implementation class or transport;
3. add the ID here first;
4. reference the new ID from the owning active specification or current-state document;
5. keep requirement wording and specification lifecycle independent from the feature name.
