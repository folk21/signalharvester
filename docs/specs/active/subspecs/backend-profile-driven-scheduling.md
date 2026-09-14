---
type: Specification
title: Backend profile-driven scheduling
description: Drive manual and automatic collection from persisted monitoring profiles with cluster-safe PostgreSQL scheduling leases.
document_role: subspec
spec_status: verification-pending
parent: ../spec-signal-harvester-platform.md
---
# Backend profile-driven scheduling

## Status

Verification pending — implementation is present and awaits the developer `./run_checks.sh` acceptance run.

## Goal

Make persisted monitoring profiles the authoritative input for collection execution and add automatic interval-based scheduling that remains safe when multiple backend replicas are running.

## Relationship to the umbrella specification

This slice advances R1 and R4. It replaces temporary caller-supplied category/global-source execution with persisted profile-owned configuration.

## Current state

Monitoring profiles are persisted with stable UUIDs, information categories, positive collection intervals, ordered source membership, and criteria. Collection can already fetch, extract, publish, and persist operational run history, but manual runs still receive category metadata from the caller and automatic scheduling is absent.

## Requirements

### R1 — collection execution is profile-driven

A collection run accepts a persisted monitoring-profile UUID. Collection loads the profile through `MonitoringProfileConfigurationProvider` and derives:

- information category;
- ordered source membership.

The caller cannot override profile-owned category or source membership.

Only enabled member sources are fetched. A source that is disabled after being added to a profile remains configured but is skipped by collection.

### R2 — manual and scheduled execution share one runner

`POST /api/v1/admin/collection-runs` accepts only `monitoringProfileId` and invokes the same `CollectionRunner` used by scheduling.

A missing profile returns HTTP 404.

### R3 — enabled profiles are scheduled by persisted interval

Collection polls enabled profiles through the configuration Java API. A profile is first due one configured interval after the scheduler first observes it. It is not executed immediately merely because the application starts.

Changing a profile interval recalculates its next due time from the moment the scheduler observes the new interval.

### R4 — scheduling is cluster-safe

Collection owns PostgreSQL schedule state. Claiming due work uses short database transactions and an exclusive lease token so two backend replicas cannot intentionally execute the same due profile concurrently.

The lease is renewed while collection work is active. External HTTP and Kafka work never runs while the claim transaction remains open.

A crashed replica eventually loses its lease after the configured lease duration, allowing another replica to claim overdue work.

### R5 — completion schedules the next interval

A successfully claimed run schedules its next due time from terminal completion, regardless of whether the run itself succeeds, partially succeeds, or fails. This avoids tight retry loops for persistently failing external sources.

### R6 — scheduler runtime bounds are configurable

The scheduler exposes configuration for:

- enable/disable state;
- polling interval;
- initial polling delay;
- lease duration;
- heartbeat interval.

The heartbeat interval must be positive and shorter than the lease duration.

## Non-goals

- cron expressions or calendar schedules;
- immediate catch-up of all missed historical intervals;
- source-test execution;
- generic REST/JSON or configurable HTML extraction;
- profile-owned analysis rules;
- scheduler-specific frontend implementation.

## Compatibility / migration

The REST manual-run request removes the temporary `informationCategory` field and requires a UUID `monitoringProfileId`. This is an intentional contract change before the corresponding frontend flow is considered stable.

Flyway `V7` adds collection-owned `monitoring_profile_schedule_state`. The table deliberately has no foreign key into configuration-owned tables; cross-module configuration is consumed through the Java API.

No Kafka/Protobuf schema change is required.

## Validation

The slice is ready for acceptance when:

1. collection unit tests verify category/source membership comes from the persisted profile and disabled members are skipped;
2. HTTP coverage verifies the UUID-only manual-run request and missing-profile behavior;
3. PostgreSQL integration coverage verifies only one of two application replicas can claim the same due profile;
4. interval changes reschedule future due work deterministically;
5. cross-module collection/analysis and HTTP smoke tests create persisted profiles instead of caller-owned profile metadata;
6. live-backend self-tests pass with temporary persisted profile creation;
7. `./run_checks.sh` passes in the developer environment.

## Implementation tasks

1. Make `CollectionRunService` resolve persisted profile configuration.
2. Narrow the manual REST request to monitoring-profile UUID.
3. Add collection-owned PostgreSQL schedule state and transactional lease coordination.
4. Add scheduled polling and lease heartbeat.
5. Update integration/live verification to create persisted profiles.
6. Synchronize OpenAPI and owning current-state documentation.
