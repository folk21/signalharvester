---
type: Specification
title: Profile-owned Analysis settings
description: Backend change specification for persisted typed Monitoring Profile analysis settings and immutable RawItemDiscovered settings snapshots.
document_role: subspec
spec_status: verification-pending
parent: ../spec-signal-harvester-platform.md
---
# Profile-owned Analysis settings

## Status

Implementation target for the current backend refinement. The code is implementation-complete in this patch but remains verification-pending until the canonical repository gate passes.

## Feature scope

- `CONFIGURATION.MONITORING_PROFILES` — persisted typed Analysis settings owned by each Monitoring Profile.
- `ANALYSIS.CLASSIFICATION` — deterministic analysis uses the settings captured for the collected item.
- `CONTRACTS.HTTP` — Monitoring Profile REST/OpenAPI exposes typed Analysis settings.
- `CONTRACTS.KAFKA_PROTOBUF` — `RawItemDiscovered` carries an additive settings snapshot.
- `EVENTING.CORRELATION` — the settings snapshot travels with the same immutable processing context as the collected item.

## Goal

Make deterministic Analysis behavior a property of the Monitoring Profile that caused collection. A queued or replayed `RawItemDiscovered` event must be analyzed with the settings captured when Collection published that event, even if the profile is edited later.

## Current state

Monitoring Profiles persist generic criteria but no typed Analysis configuration. `KeywordContentAnalyzer` currently reads one deployment-global keyword list and minimum-match threshold. Collection already loads the effective Monitoring Profile before publishing each raw event, so it can snapshot typed settings without introducing a new synchronous dependency from Analysis to Configuration.

## Requirements

### R1 — typed profile settings

A Monitoring Profile must expose typed keyword Analysis settings containing:

- one or more keywords;
- `minimumMatches` greater than zero and no greater than the number of unique normalized keywords.

Keywords are trimmed, case-normalized, deduplicated in first-occurrence order, and persisted as profile-owned configuration.

### R2 — REST/OpenAPI contract

`MonitoringProfile` responses must always include `analysisSettings`.

`MonitoringProfileUpsertRequest.analysisSettings` is optional only as a compatibility bridge:

- create without the field uses the deployment compatibility defaults;
- update without the field preserves the profile's current effective settings.

New clients should always round-trip `analysisSettings`, including replacement-style enabled/disabled updates.

### R3 — persisted ownership

New or updated profiles must persist their effective Analysis settings in Configuration-owned PostgreSQL data.

Profiles created before the settings migration may temporarily have no explicit persisted settings. Their effective settings come from the same deployment-global compatibility defaults that previously drove Analysis. Once such a profile is updated, its effective settings are persisted explicitly.

### R4 — immutable event snapshot

Collection must copy the effective profile Analysis settings into every newly published `RawItemDiscovered` event.

The Protobuf change must be additive within `collection.v1`; existing field numbers and semantics remain unchanged.

### R5 — Analysis authority

For a raw event that contains the settings snapshot, Analysis must use only that snapshot for deterministic keyword classification. Analysis must not synchronously query Configuration while consuming the event.

`KeywordContentAnalyzer` must remain stateless with respect to profile rules.

### R6 — legacy event compatibility

A previously published `RawItemDiscovered` event without an Analysis settings snapshot must remain processable. Analysis uses the existing deployment-global keyword configuration only for those legacy events.

### R7 — validation and failures

Invalid HTTP settings must produce backend-authoritative client errors. Invalid settings embedded in an incoming Kafka event are deterministic mapping failures and follow the existing non-retryable Analysis dead-letter path.

## Processing sequence

1. Configuration resolves the Monitoring Profile and its effective Analysis settings.
2. Collection fetches/extracts source items for that profile.
3. Collection publishes `RawItemDiscovered` with the effective settings snapshot.
4. Analysis maps the Protobuf snapshot to an Analysis-owned immutable settings value.
5. Normalization and profile-scoped deduplication run as before.
6. The stateless keyword analyzer evaluates the normalized item using the captured settings.
7. Existing transactional-outbox terminal publication semantics remain unchanged.

## Compatibility / migration

- Add a nullable `analysis_minimum_matches` column and ordered keyword child table in a new Configuration Flyway migration.
- Existing rows remain nullable so deployment overrides from the previous global behavior are not silently replaced during SQL migration.
- Repository reads materialize those legacy rows with the existing `signalharvester.analysis.keyword-rules` compatibility defaults.
- New/updated rows persist explicit settings.
- Add `analysis_settings` as field 12 of `RawItemDiscovered`; no existing Protobuf field number changes.
- Legacy raw events without field 12 continue to use the same global compatibility defaults in Analysis.

## Non-goals

- generic rule-engine design;
- LLM/embedding/provider-backed Analysis;
- dynamic Analysis lookup of the latest Monitoring Profile during Kafka consumption;
- category-specific rule schemas beyond the current deterministic keyword analyzer;
- automatic replay or historical reclassification when a Monitoring Profile is edited;
- frontend implementation.

## Validation

The stage is ready for acceptance when automated verification proves:

1. Monitoring Profile persistence and REST round-trip typed Analysis settings;
2. invalid keyword/minimum settings are rejected;
3. update omission preserves existing settings for compatibility;
4. legacy persisted profiles resolve the configured compatibility defaults;
5. Collection publishes the settings snapshot in `RawItemDiscovered`;
6. Protobuf round-trip and additive-field compatibility remain valid;
7. Analysis uses the event snapshot rather than current deployment defaults;
8. legacy raw events without a snapshot still use deployment defaults;
9. the cross-module Collection -> Kafka -> Analysis path classifies with profile-owned settings;
10. `./run_checks.sh` passes.

## Implementation tasks

1. Add the typed Configuration API value and persisted schema.
2. Extend Monitoring Profile application, JDBC, HTTP, and OpenAPI contracts.
3. Add the additive `RawItemDiscovered` settings snapshot and Collection mapping.
4. Refactor deterministic Analysis to consume per-event settings with legacy fallback.
5. Update focused unit/integration/contract tests and owning documentation.
6. Run focused checks followed by the canonical repository gate.
