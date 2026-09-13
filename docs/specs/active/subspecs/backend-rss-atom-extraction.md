---
type: Specification
title: Backend RSS/Atom item extraction
description: Collection-owned extraction of RSS and Atom responses into bounded semantic raw items before Kafka publication.
document_role: subspec
spec_status: verification-pending
parent: ../spec-signal-harvester-platform.md
---
# Backend RSS/Atom item extraction

## Status

Verification pending — implementation is complete and awaits the developer `./run_checks.sh` acceptance run.

## Goal

Turn one fetched RSS/Atom document into bounded individual collection items so downstream Analysis and Results operate on feed entries rather than on the complete XML response body.

## Relationship to the umbrella specification

This slice advances umbrella requirements R2 and R3 by introducing the first reusable source-specific extraction strategy. Scheduling/profile ownership and interactive source-test UI remain separate work.

## Current state

Collection already fetches configured REST/RSS/HTML URLs through one bounded HTTP transport and publishes `RawItemDiscovered` events. Before this slice every successful HTTP response produced exactly one raw item, so an RSS feed containing many entries was analyzed as one XML document.

## Requirements

### R1 — extraction remains Collection-owned

Source-specific parsing occurs after successful HTTP fetch and before the Kafka publication boundary. Analysis must not parse RSS or Atom.

### R2 — RSS and Atom entries become individual raw items

RSS 2.x `<item>` and Atom `<entry>` elements produce separate `RawItemDiscovered` events. When available, source identity, title, item URL, publication time, and textual content populate the existing event fields.

### R3 — non-feed behavior remains compatible

REST and HTML sources retain one-response-per-item behavior. Their raw-item identity material remains the original response bytes so existing deterministic identities are preserved.

### R4 — bounded extraction

The number of entries accepted from one RSS/Atom response is configurable and bounded. Exceeding the configured maximum is an explicit extraction failure rather than an unbounded allocation or silent truncation.

### R5 — secure XML parsing

DTD declarations and external entities must not be processed. RSS/Atom parsing uses JDK XML facilities with external DTD/schema access disabled.

### R6 — failure isolation

Malformed feed content produces an `EXTRACTION_FAILED` operational outcome for that source without cancelling unrelated source work. A valid feed with no entries produces `NO_ITEMS` and is not considered a run failure.

### R7 — per-item operational outcomes

A source may contribute multiple terminal outcomes to one run. Published and failed item outcomes retain the configured source id; run-level `publishedCount` counts successfully published semantic items and `failedCount` counts fetch, extraction, and publication failures.

### R8 — live black-box verification

Repository tooling must provide an opt-in command for a separately running backend that can optionally import a source manifest, start a manual collection run, and observe correlated materialized Results only through public REST APIs. It must also provide a deterministic host-local two-entry RSS fixture mode that verifies per-entry publication/materialization without public-network dependency. The live command must not run from the normal repository verification lifecycle.

## Non-goals

- generic configurable JSON/REST item extraction;
- configurable HTML selectors;
- feed pagination/history crawling;
- sanitizing arbitrary HTML for browser rendering;
- scheduling or monitoring-profile persistence;
- source-test UI;
- live backend checks inside `run_checks.sh`.

## Compatibility / migration

No Kafka/Protobuf schema change is required because `RawItemDiscovered` already contains `externalId`, `title`, `url`, `content`, `contentType`, and `publishedAt` fields.

Collection run history adds `NO_ITEMS` and `EXTRACTION_FAILED` status values through the globally coordinated Flyway `V5` migration. REST/OpenAPI adds those enum values without changing existing response fields.

## Validation

The slice is ready for acceptance when:

1. deterministic unit coverage verifies RSS 2.x, Atom, malformed XML, DTD rejection, item bounds, empty feeds, and passthrough identity compatibility;
2. collection orchestration verifies multiple publications from one feed plus no-items/extraction-failure semantics;
3. PostgreSQL history round-trips the new statuses;
4. Kafka mapping preserves extracted semantic metadata;
5. live-backend tooling has deterministic loopback self-tests and a host-local two-entry RSS verification mode;
6. `./run_checks.sh` passes in the developer environment.

## Implementation tasks

1. Introduce a collection-owned extracted-item model and extraction boundary.
2. Add bounded secure RSS/Atom parsing.
3. Publish one raw Kafka event per extracted entry.
4. Extend collection run/history status semantics for feed extraction.
5. Add the live-backend black-box trial tool.
6. Synchronize OpenAPI, runtime configuration, current-state docs, and tests.
