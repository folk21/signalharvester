---
type: Specification
title: All-relevant default Analysis settings
description: Bounded backend change that makes omitted Monitoring Profile keyword filtering classify new analyzed items as relevant while preserving legacy compatibility behavior.
document_role: subspec
spec_status: verification-pending
parent: ../spec-signal-harvester-platform.md
---
# All-relevant default Analysis settings

## Status

Implementation is complete and verification is pending.

## Feature scope

- `CONFIGURATION.MONITORING_PROFILES`
- `ANALYSIS.CLASSIFICATION`
- `CONTRACTS.HTTP`
- `CONTRACTS.KAFKA_PROTOBUF`

## Goal

Make the intuitive Monitoring Profile default explicit: when no keyword relevance filter is configured, newly analyzed items are relevant. Keyword matching remains available as an opt-in deterministic filter.

## Requirements

### AR1 — explicit all-relevant state

Effective Analysis settings must support `keywords=[]` with `minimumMatches=0`. This state means every analyzed item is relevant.

A non-empty keyword list must continue to require `minimumMatches >= 1` and no greater than the normalized unique keyword count. Mixed states such as empty keywords with a positive threshold or non-empty keywords with zero threshold must be rejected.

### AR2 — create/update semantics

A Monitoring Profile create request that omits `analysisSettings` must persist the explicit all-relevant state.

A replacement update that omits `analysisSettings` must continue to preserve the profile's current effective settings.

Profiles persisted before typed settings existed remain distinguishable by `analysis_minimum_matches IS NULL` plus no keyword rows. Those legacy rows must continue resolving deployment compatibility defaults until updated.

### AR3 — immutable event snapshot

Collection must continue snapshotting the effective settings into `RawItemDiscovered` without changing the Protobuf field layout. Empty `keywords` plus `minimum_matches=0` represents the all-relevant state on the existing wire shape.

Legacy raw events without the settings field must continue using deployment compatibility defaults.

### AR4 — deterministic classification

Analysis must classify the all-relevant state as:

- `relevant=true`;
- `classification=ALL_RELEVANT`;
- `score=100`;
- no keyword tags;
- an explanation that states no keyword filter is configured.

Keyword-filtered behavior remains unchanged.

## Compatibility

The REST shape remains additive-compatible: `keywords` and `minimumMatches` stay present in effective response settings. Their allowed value domain expands to include the explicit all-relevant state.

The Kafka schema field numbers and types do not change. Only the documented semantic combination `[]/0` is added.

Existing explicit keyword profiles are unchanged. Existing pre-migration rows and legacy raw events retain compatibility-default behavior.

## Validation

- Configuration unit tests cover `[]/0` and invalid mixed states.
- Configuration PostgreSQL/controller integration verifies create omission persists and returns `[]/0`.
- Analysis unit tests verify `ALL_RELEVANT` classification.
- Event-contract serialization verifies the empty-keyword snapshot round trip.
- Run the canonical backend `./run_checks.sh` before acceptance.
