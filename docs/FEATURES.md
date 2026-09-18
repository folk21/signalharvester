---
type: Feature Catalog
title: SignalHarvester feature vocabulary
description: Stable human-readable feature identifiers used across specifications, documentation, tests, and key implementation entry points.
---
# SignalHarvester feature vocabulary

## Purpose

This document owns the stable feature IDs used across SignalHarvester documentation and code references.

A feature ID names a durable system capability. It does not name a requirement or a specification lifecycle stage.

Keep these concepts separate:

- **Feature ID** — a long-lived capability name, for example `ANALYSIS.OUTBOX`.
- **Requirement ID** — a normative rule inside one specification, for example `R22` or `A5`.
- **Specification** — one planned or in-progress change, for example `backend-authentication-authorization`.

A feature may be implemented or refined by several specifications. Archiving a specification does not retire its feature IDs.

## Identifier format

Feature IDs use uppercase ASCII words.

- Use `.` between capability levels: `RESULTS.LIVE`.
- Use `_` inside a multi-word level: `DIAGNOSTICS.EVENT_OBSERVATION`.
- Prefer two levels.
- Add a third level only when it improves navigation.
- Do not encode dates, versions, requirement numbers, implementation stages, Java packages, or deployment topology.
- Once an ID is referenced outside this catalog, keep it stable. Rename it only through an explicit repository-wide vocabulary migration.

The dot is a naming hierarchy only. It does not define authorization or inheritance. For example, `SECURITY.AUTHORIZATION` does not inherit semantics from another security feature.

## Usage rules

Use feature IDs when they improve navigation or make capability ownership clear.

Useful locations include:

- umbrella and sub-spec feature-scope sections;
- requirement cross-references;
- owning architecture or implementation documentation;
- important application entry points and integration boundaries;
- feature-level tests.

Do not tag every helper, DTO, mapper, or private implementation class. Avoid metadata noise.

When a Java type or feature-level test benefits from a reference, use ordinary Javadoc:

```java
/**
 * Dispatches committed Analysis outbox records to Kafka.
 *
 * <p>Feature: {@code ANALYSIS.OUTBOX}.</p>
 */
```

Tests and documents must use IDs from this catalog. Do not invent local feature codes.

## Catalog

### Configuration and collection

- `CONFIGURATION.MONITORING_PROFILES` — persisted monitoring profiles, source membership, criteria, collection interval, and typed Analysis settings. Owner: Configuration. Related umbrella requirement: R1.
- `CONFIGURATION.SOURCES` — persisted external-source configuration. Owner: Configuration. Related umbrella requirement: R2.
- `COLLECTION.SOURCE_TEST` — diagnostic persisted-source fetch and extraction preview. Owner: Collection. Related umbrella requirement: R3.
- `COLLECTION.SCHEDULING` — cluster-safe scheduled collection for enabled profiles. Owner: Collection. Related umbrella requirement: R4.
- `COLLECTION.RUNS` — explicit manual and scheduled collection-run identity and outcomes. Owner: Collection. Related umbrella requirement: R5.
- `COLLECTION.ADAPTERS` — replaceable external-source collection and extraction adapters. Owner: Collection. Related umbrella requirement: R6.

### Eventing and analysis

- `EVENTING.PIPELINE` — Kafka-backed asynchronous processing between major stages. Owner: producing and consuming modules. Related umbrella requirement: R7.
- `EVENTING.CORRELATION` — stable event, run, item, and trace correlation. Owner: Event Contracts and producing/consuming modules. Related umbrella requirement: R8.
- `ANALYSIS.NORMALIZATION` — conversion of collected records into normalized content. Owner: Analysis. Related umbrella requirement: R9.
- `ANALYSIS.DEDUPLICATION` — deterministic profile-scoped duplicate handling. Owner: Analysis. Related umbrella requirement: R10.
- `ANALYSIS.CLASSIFICATION` — replaceable deterministic or future provider-backed analysis using immutable per-item rule snapshots where required. Owner: Analysis. Related umbrella requirement: R11.
- `ANALYSIS.OUTBOX` — transactional staging and at-least-once publication of terminal Analysis events. Owner: Analysis. Related umbrella requirement: R22.

### Results and presentation

- `RESULTS.MATERIALIZATION` — Results-owned durable analyzed-result projection. Owner: Results. Related umbrella requirement: R12.
- `RESULTS.LIVE` — resumable browser live-result delivery over SSE. Owner: Results. Related umbrella requirement: R13.
- `PRESENTATION.CONFIGURATION` — browser configuration workflows over backend contracts. Owner: `signalharvester-web`. Related umbrella requirement: R14.
- `RESULTS.BROWSING` — browser result list and detail inspection over Results APIs. Owner: Results + `signalharvester-web`. Related umbrella requirement: R15.
- `PRESENTATION.VIEWER_RESULTS` — consumer-facing result experience without internal operational detail. Owner: `signalharvester-web`. Related umbrella requirement: R32.

### Diagnostics and observability

- `DIAGNOSTICS.ANALYSIS_INSPECTION` — bounded operational inspection of Analysis normalization and deduplication state. Owner: Analysis. Related umbrella requirement: R32.
- `DIAGNOSTICS.EVENT_OBSERVATION` — bounded technical event history and live observation. Owner: Event Observation. Related umbrella requirements: R16 and R28.
- `DIAGNOSTICS.PROCESSING_FLOW` — run/item processing-flow reconstruction. Owner: Event Observation. Related umbrella requirement: R17.
- `OBSERVABILITY.APPLICATION` — metrics, structured logs, distributed traces, and health/readiness. Owner: App + producing/consuming modules. Related umbrella requirement: R18.
- `OBSERVABILITY.INFRASTRUCTURE` — infrastructure telemetry and Grafana-oriented operational views. Owner: Infrastructure. Related umbrella requirement: R19.

### Reliability and runtime

- `RELIABILITY.KAFKA_RETRY` — bounded retry for asynchronous Kafka processing failures. Owner: consuming modules. Related umbrella requirement: R20.
- `RELIABILITY.DEAD_LETTER` — terminal dead-letter handling plus controlled owner-specific operator recovery for poison or exhausted Kafka records. Owner: consuming modules + Event Contracts. Related umbrella requirement: R20.
- `RELIABILITY.IDEMPOTENCY` — duplicate-safe consumer and persistence behavior. Owner: consuming modules. Related umbrella requirement: R21.
- `RUNTIME.CONCURRENCY` — explicit blocking/streaming execution and bounded external-I/O concurrency. Owner: App + producing/consuming modules. Related umbrella requirement: R23.
- `SCALABILITY.KAFKA_CONSUMERS` — horizontal Kafka consumer scaling where partitioning permits. Owner: consuming modules + deployment. Related umbrella requirement: R26.

### Security

- `SECURITY.EXTERNAL_SOURCE_ACCESS` — safe outbound source access, secret separation, and destination-policy constraints. Owner: Configuration + Collection. Related umbrella requirements: R23 and R30.
- `SECURITY.IDENTITY_ROLES` — persisted identities and explicit additive role assignments. Owner: Security. Related umbrella requirement: R31.
- `SECURITY.AUTHENTICATION` — stateless signed-JWT request authentication, including browser cookie transport. Owner: Security. Related umbrella requirement: R31.
- `SECURITY.AUTHORIZATION` — backend-enforced role/capability access control. Owner: Security + App. Related umbrella requirements: R31 and R32.

### Platform, delivery, and testing

- `PLATFORM.MODULAR_MONOLITH` — one deployable backend with explicit functional-module boundaries. Owner: repository architecture. Related umbrella requirement: R25.
- `CONTRACTS.KAFKA_PROTOBUF` — versioned Protobuf Kafka integration contracts. Owner: Event Contracts. Related umbrella requirements: R7 and R8.
- `CONTRACTS.HTTP` — explicit REST/OpenAPI and SSE/JSON browser contracts. Owner: API Contracts + owning modules. Related umbrella requirement: R25.
- `DEPLOYMENT.KUBERNETES` — local production-style Kubernetes deployment. Owner: Infrastructure. Related umbrella requirement: R24.
- `DELIVERY.FRONTEND_BACKEND_BOUNDARY` — independently buildable frontend/backend repositories joined by explicit contracts. Owner: backend + `signalharvester-web`. Related umbrella requirement: R25.
- `DATA.PROVENANCE` — retained source/run provenance for collected results. Owner: Collection + Analysis + Results. Related umbrella requirement: R27.
- `TESTING.DETERMINISTIC_LOCAL` — deterministic tests without public Internet dependencies. Owner: repository testing. Related umbrella requirement: R29.

## Adding a feature ID

Add an ID only when the capability should remain meaningful beyond one patch or specification.

Before adding an ID:

1. Check this catalog for an existing capability with the same meaning.
2. Name the owning capability, not the current implementation class or transport detail.
3. Add the ID here first.
4. Reference it from the owning active specification or current-state document.
5. Keep requirement wording and specification lifecycle independent from the feature name.
