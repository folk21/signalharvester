---
type: Specification
title: SignalHarvester normalization, deduplication, and minimal analysis
description: Current implementation sub-specification for consuming RawItemDiscovered, normalizing content, durable profile-scoped deduplication, deterministic rule analysis, and terminal analysis events.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: completed
---
# SignalHarvester normalization, deduplication, and minimal analysis

## Status

Completed and verified — archived after the analysis PostgreSQL/Kafka, focused processing, rollback/no-commit, and cross-module integration suites passed in the developer environment.

This slice implements the first real asynchronous consumer path after collection. It deliberately stops before results persistence/read APIs and before monitoring-profile-owned analysis rules.

## Goal

Implement the first deterministic processing pipeline:

```text
RawItemDiscovered bytes
    -> analysis Kafka listener
    -> analysis-owned raw model
    -> normalization
    -> stable logical identity
    -> profile-scoped durable deduplication
    -> deterministic ContentAnalyzer
    -> ItemAnalyzed

rediscovered logical item
    -> ItemRejected(reason=DUPLICATE)
```

The analysis core must remain independent of generated Protobuf and Kafka implementation types so another analyzer can replace the initial keyword rules without changing transport contracts.

## Relationship to the umbrella specification

This sub-spec directly advances umbrella requirements R8, R9, R10, R11, R20, R21, R27, and R29 and implements the normalization/deduplication/analysis part of scenarios S1, S3, S4, and S5.

The results module remains the owner of authoritative user-facing result persistence. Analysis persistence introduced here exists only to own deduplication state required by the analysis process.

## Current state

Baseline available before this slice:

- collection publishes acknowledged `RawItemDiscovered` Protobuf bytes;
- every raw event carries event identity, collection-run correlation, source/profile/category provenance, URL, content, and raw-item identity;
- collection owns stable raw payload identity but explicitly does not own normalized logical-item deduplication;
- no Kafka consumer exists yet;
- `modules:analysis` is still a source skeleton;
- the shared PostgreSQL datasource currently applies the configuration module's `V1` Flyway migration.

Implemented in this slice:

- version-one `ItemAnalyzed` and `ItemRejected` Protobuf contracts;
- an analysis Kafka listener that consumes `RawItemDiscovered` as `byte[]` and maps generated Protobuf only at the adapter boundary;
- explicit manual offset commit only after processing and terminal Kafka publication complete;
- analysis-owned `DiscoveredRawItem` and `NormalizedContentItem` models;
- deterministic whitespace and URL normalization;
- stable normalized logical identity that prefers `sourceId + externalId` and otherwise fingerprints `sourceId + normalized URL + normalized content`;
- PostgreSQL-backed deduplication claims scoped by `(monitoringProfileId, normalizedItemId)`;
- duplicate discovery counters/provenance in the analysis-owned schema;
- replaceable `ContentAnalyzer` application boundary;
- deterministic configurable keyword analyzer with relevance, classification, score, tags, and explanation;
- acknowledged `ItemAnalyzed`/`ItemRejected` Kafka publication using normalized item id as the Kafka key;
- cross-module collection -> Kafka -> analysis integration coverage including equivalent rediscovery with different raw-item ids.

## Requirements

### R1 — generated Protobuf stays at Kafka boundaries

`RawItemDiscovered`, `ItemAnalyzed`, and `ItemRejected` generated Java types may be used by `analysis.event.kafka` adapters/mappers only.

Normalization, deduplication, analyzer implementations, and persistence must depend on analysis-owned Java models rather than generated transport messages.

An analysis-owned raw model is not a handwritten wire DTO: it is the semantic application representation that terminates transport coupling before core processing.

### R2 — normalization precedes deduplication and analysis

Every valid consumed raw item must be mapped into `NormalizedContentItem` before duplicate detection or analyzer logic depends on its content.

The baseline normalizer must deterministically:

- trim leading/trailing text;
- collapse repeated Unicode whitespace;
- normalize URI dot segments;
- lowercase URI scheme/host;
- remove default HTTP/HTTPS ports;
- preserve query parameters;
- preserve source/profile/category/event provenance;
- retain source publication time when supplied.

Source-specific HTML/RSS/API extraction remains collection-owned and is not introduced here.

### R3 — normalized identity prefers explicit external identity

When `externalId` is available, normalized logical identity must derive from source identity plus the stable external id and must not change merely because item text or URL changes.

When `externalId` is unavailable, identity must use a deterministic SHA-256 fingerprint over source identity, normalized URL, and normalized content.

Collection run id, raw event id, fetch time, and monitoring profile id must not change `normalizedItemId`.

### R4 — deduplication is monitoring-profile scoped

The same logical external item may legitimately participate in multiple monitoring profiles.

Therefore `normalizedItemId` identifies the logical external item globally, while the durable duplicate claim is keyed by:

```text
monitoringProfileId + normalizedItemId
```

A rediscovery in the same profile is a duplicate. The same logical item in another profile is accepted independently.

### R5 — deduplication state is durable and observable

The analysis module owns PostgreSQL schema `analysis` and table `normalized_item_claims`.

The persisted claim must retain enough data to diagnose duplicate processing:

- monitoring profile id;
- normalized item id;
- source id;
- external id when available;
- source URL;
- first/last raw item id;
- first/last source event id;
- first/last seen time;
- discovery count.

Duplicate discovery increments the persisted count and emits `ItemRejected` with reason `DUPLICATE` rather than silently disappearing.

### R6 — deduplication claim and terminal publication share one application transaction window

For a new item, the first claim is inserted before the terminal `ItemAnalyzed` publication. For a duplicate, duplicate-observation state is updated before `ItemRejected` publication.

Both operations execute inside the analysis use case's caller-owned JDBC write transaction.

If terminal Kafka publication throws, the JDBC transaction must roll back and the consumed raw offset must not be committed. Redelivery can retry the item without a permanently orphaned deduplication claim.

This does not provide distributed exactly-once behavior. If Kafka acknowledges an output but the subsequent PostgreSQL commit fails, redelivery may publish the terminal event again. Downstream consumers must remain idempotent by normalized item identity until transactional outbox or another stronger consistency mechanism is introduced.

### R7 — initial analysis is deterministic and replaceable

Analysis must use the `ContentAnalyzer` application interface.

The first implementation is a configurable keyword analyzer; no external LLM, SaaS, or network call is required.

For each accepted item it must produce:

- `relevant` boolean;
- stable classification;
- score from 0 through 100;
- matched keyword tags;
- human-readable explanation;
- stable analyzer/rule identifier.

The current keyword configuration is global runtime configuration only. Monitoring profiles will later own per-profile rules without changing the `ContentAnalyzer` boundary.

### R8 — valid new items produce ItemAnalyzed

Every valid, newly claimed normalized item must publish one acknowledged `ItemAnalyzed` event carrying:

- a new analysis event id;
- original collection correlation id and traceparent when available;
- original raw source event id;
- raw and normalized item identities;
- source/profile/category provenance;
- normalized content/title/URL and content type;
- category-specific attributes;
- analyzer result and explanation;
- source publication time when available.

The Kafka key must be `normalizedItemId`.

Relevant and irrelevant valid items both produce `ItemAnalyzed`; relevance is an analysis outcome rather than a transport rejection.

### R9 — duplicates produce ItemRejected

A duplicate in the same monitoring-profile scope must not run the analyzer again.

It must publish acknowledged `ItemRejected` with:

- a new event id;
- current raw-event correlation/trace context;
- current source event id and raw item id;
- normalized item id;
- source/profile/category provenance;
- reason code `DUPLICATE`;
- human-readable explanation.

The Kafka key must be `normalizedItemId`.

### R10 — raw Kafka offset commits only after terminal success

The analysis consumer must disable automatic offset commits.

It may commit the current record only after:

1. Protobuf decoding succeeds;
2. transport-to-domain mapping succeeds;
3. normalization succeeds;
4. deduplication persistence succeeds;
5. either duplicate rejection or analysis completes;
6. terminal Kafka publication is acknowledged;
7. the analysis transaction returns successfully.

Any exception before that point must leave the record uncommitted for the current retry/error strategy.

### R11 — one shared datasource requires globally unique Flyway versions

Migrations remain physically owned by their modules in separate locations such as:

```text
classpath:db/migration/configuration
classpath:db/migration/analysis
```

However, because both locations are applied by one Flyway datasource/schema-history table, version numbers must be globally unique across those locations. The existing configuration migration remains `V1`; the first analysis migration is therefore `V2`.

### R12 — malformed/poison-message policy remains explicit future work

This slice validates decoded raw items and throws for malformed Protobuf, key/payload identity mismatch, invalid URI/provenance, or other invalid transport data.

Such failures intentionally remain uncommitted. Bounded retry, terminal poison-message classification, and DLQ publication belong to the later reliability slice rather than being silently approximated here.

## Scenarios

### S1 — new relevant item

A `RawItemDiscovered` record contains content matching configured keywords.

Analysis normalizes it, creates a first profile-scoped deduplication claim, executes the deterministic analyzer, publishes `ItemAnalyzed`, commits the database transaction, then commits the raw Kafka offset.

### S2 — valid but irrelevant item

The item is new but does not meet the configured minimum keyword matches.

It still publishes `ItemAnalyzed` with `relevant=false`, a deterministic non-match classification/score, and explanation. Results may later decide how irrelevant items are exposed or persisted.

### S3 — equivalent rediscovery with different raw bytes

Two collection runs fetch semantically equivalent text whose whitespace differs, so collection assigns different `rawItemId` values.

Analysis normalizes both texts to the same representation. The first produces `ItemAnalyzed`; the second produces `ItemRejected(reason=DUPLICATE)` and increments the durable discovery count.

### S4 — same external item used by two profiles

Two raw events carry the same source/external identity but different monitoring-profile ids.

Both share the same `normalizedItemId`, but each profile obtains its own first deduplication claim and both are analyzed.

### S5 — output Kafka publication fails

The analysis producer fails before acknowledging the terminal event.

The current JDBC transaction rolls back and the raw input offset is not committed. A later retry may attempt processing again.

## Non-goals

This slice does not implement:

- results-module PostgreSQL persistence;
- result query REST APIs or SSE;
- monitoring profiles or profile-owned analysis rule persistence;
- source-specific HTML/RSS/API extraction;
- LLM/embedding analysis;
- semantic/vector similarity deduplication;
- Kafka DLQ/retry policy;
- transactional outbox;
- exactly-once PostgreSQL + Kafka semantics;
- persisted collection-run history;
- Event Explorer/event-observation projection;
- public manual collection trigger or scheduler.

## Design constraints

- Keep the analysis module functional without an LLM or public network access.
- Keep generated Protobuf and Kafka classes inside analysis Kafka adapters.
- Keep deduplication persistence owned by analysis; results must not query this table directly.
- Keep normalized item identity independent from monitoring-profile scope.
- Keep output publication acknowledged/blocking so offset ownership is explicit.
- Do not introduce a generic workflow/rules framework.
- Preserve stable source/correlation/trace provenance through analysis output events.
- Do not log complete configured URLs.

## Compatibility / migration

This slice adds compatible new version-one event families under `analysis/v1`; it does not modify the published field numbers of `RawItemDiscovered` or `EventEnvelope`.

The existing runtime Flyway configuration adds `classpath:db/migration/analysis` to the same datasource. Because one Flyway history is shared, future migrations in any module using that datasource must coordinate globally unique increasing versions unless the Flyway topology is deliberately changed.

No REST contract changes are introduced.

## Validation

Focused validation:

```bash
./gradlew :contracts:event-contracts:test :modules:analysis:test :testing:integration-tests:test --no-watch-fs
```

Broader validation after the focused suite passes:

```bash
./gradlew test --no-watch-fs
```

Acceptance requires tests covering:

1. `ItemAnalyzed` and `ItemRejected` Protobuf serialization/unknown-field tolerance;
2. deterministic text/URL normalization;
3. external-id-preferred and fingerprint-fallback normalized identity;
4. profile-scoped PostgreSQL deduplication and duplicate observation count;
5. deterministic keyword relevance/classification/score/explanation;
6. analysis output Kafka key/topic/Protobuf mapping;
7. raw listener consumption using byte-array Protobuf transport;
8. raw offset commit occurring only after processing success and remaining absent when processing/output fails;
9. cross-module persisted source -> HTTP -> collection raw Kafka -> analysis flow;
10. equivalent rediscovery creating one `ItemAnalyzed` and one duplicate `ItemRejected` despite different collection raw-item ids.

## Implementation tasks

1. Add version-one `ItemAnalyzed` and `ItemRejected` Protobuf contracts and contract tests.
2. Establish the analysis module Micronaut/Kafka/JDBC/Flyway dependencies.
3. Add analysis-owned decoded-raw and normalized content models.
4. Implement deterministic normalization and normalized identity.
5. Add analysis-owned PostgreSQL deduplication migration/repository.
6. Add replaceable `ContentAnalyzer` and deterministic configurable keyword implementation.
7. Implement terminal analysis Kafka mapper/publisher.
8. Implement the raw-item Kafka listener with explicit offset ownership.
9. Add focused normalization/rules/persistence tests.
10. Add collection -> Kafka -> analysis Testcontainers integration coverage.
11. Synchronize implementation/configuration/testing/roadmap documentation.
12. After developer validation is green, archive this sub-spec and move focus to results persistence + read REST.
