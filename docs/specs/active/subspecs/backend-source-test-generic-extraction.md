---
type: Specification
title: Backend source testing and generic extraction
description: Add bounded diagnostic source testing plus configuration-driven REST/JSON and HTML extraction on the existing Collection boundary.
document_role: subspec
spec_status: verification-pending
parent: ../spec-signal-harvester-platform.md
---
# Backend source testing and generic extraction

## Status

Verification pending — implementation is present and awaits the developer `./run_checks.sh` acceptance run.

## Goal

Make reusable source types useful without backend code changes for each compatible source. Add diagnostic source testing that uses the same fetch and extraction boundaries as normal collection without inserting diagnostic data into the production event flow.

## Relationship to the umbrella specification

This slice advances R2 and R3. It also strengthens the generic-source part of R4 because newly configured REST/JSON and HTML sources can participate in later scheduled profile runs without redeployment.

## Current state

Collection already owns bounded HTTP fetching, RSS/Atom extraction, profile-driven manual execution, and cluster-safe interval scheduling. REST and HTML sources without dedicated extraction logic still produce one passthrough item containing the complete response body. The public API has no diagnostic source-test operation.

## Requirements

### R1 — source testing uses the normal Collection boundary

`POST /api/v1/sources/{sourceId}/test` loads one persisted source through `SourceConfigurationProvider`, then uses the same `ExternalSourceClient` and `SourceItemExtractor` implementations used by normal collection.

A disabled source may be tested before activation.

The test operation must not:

- publish `RawItemDiscovered` or other Kafka events;
- create collection-run history;
- modify the persisted source configuration.

### R2 — source-test diagnostics are bounded and explicit

A source-test response reports:

- terminal diagnostic status;
- HTTP status when a response was received;
- response content type and bounded response byte count metadata;
- fetch duration;
- extraction duration;
- number of extracted candidate items;
- a bounded preview of extracted items;
- a failure message for fetch or extraction failures.

Preview item count and content length are runtime-configurable and bounded.

A missing persisted source returns HTTP 404. A fetch or extraction failure returns HTTP 200 with a diagnostic failure status so the UI can present the external-source problem as the result of the test operation.

### R3 — REST JSON extraction is configuration-driven

A REST source opts into JSON extraction when any persisted `json.*` setting is present.

Supported settings are:

- `json.itemsPointer` — optional RFC 6901 JSON Pointer selecting the candidate array or object; empty/root is the default;
- `json.contentPointer` — required pointer relative to each candidate;
- `json.externalIdPointer` — optional scalar identity field;
- `json.titlePointer` — optional scalar title field;
- `json.urlPointer` — optional scalar absolute or relative HTTP(S) URL field;
- `json.publishedAtPointer` — optional scalar timestamp field.

The candidate selector must resolve to an object or array. Structured content values remain JSON; scalar content becomes UTF-8 text.

### R4 — HTML extraction is configuration-driven

An HTML source opts into selector extraction when any persisted `html.*` setting is present.

Supported settings are:

- `html.itemSelector` — required CSS selector identifying repeated candidate elements;
- `html.contentSelector` — optional selector relative to each candidate; candidate text is the default content;
- `html.titleSelector` — optional title selector;
- `html.externalIdSelector` and `html.externalIdAttribute` — optional identity source; when only the attribute is supplied it is read from the candidate element;
- `html.urlSelector` and `html.urlAttribute` — optional URL source; the attribute defaults to `href`;
- `html.publishedAtSelector` and `html.publishedAtAttribute` — optional timestamp source.

Text extraction uses parsed HTML text rather than regex stripping. Relative URLs resolve against the fetched source URL.

### R5 — generic extraction is bounded

REST/JSON and HTML extraction share a configurable maximum candidate-item count. Responses above that bound fail extraction explicitly instead of being silently truncated or allocating an unbounded item list.

The existing HTTP response-size bound remains authoritative before parsing begins.

### R6 — existing source behavior remains compatible

RSS/Atom behavior remains unchanged.

REST sources without `json.*` settings and HTML sources without `html.*` settings retain the existing one-response passthrough behavior and raw-body identity material.

### R7 — semantic identity remains deterministic

Configuration-driven JSON and HTML extraction calculate raw-item identity from stable extracted semantic fields: external id, title, resolved URL, content, and publication time.

Equivalent extracted items therefore retain stable raw identities across repeated runs.

## Non-goals

- testing an unsaved draft source payload;
- custom HTTP methods, headers, credentials, or request bodies;
- JavaScript/browser rendering for HTML sources;
- general JSONPath expressions beyond RFC 6901 JSON Pointer;
- arbitrary scraper scripting;
- publishing diagnostic source-test results to Kafka;
- frontend source-test UI implementation;
- outbound SSRF/network-destination policy.

## Compatibility / migration

No database migration or Kafka/Protobuf schema change is required. Existing `source_settings` storage already supports additive string settings.

The REST contract adds one operation and diagnostic response schemas. Existing source CRUD payloads remain compatible.

Collection adds Jackson Databind for JSON tree parsing and jsoup for standards-aware HTML parsing and CSS selectors.

## Validation

The slice is ready for acceptance when:

1. JSON extractor tests cover arrays, single objects, semantic fields, malformed pointers, and item bounds;
2. HTML extractor tests cover repeated candidates, relative URLs, attributes, default content, malformed selectors, and item bounds;
3. source-test service tests cover disabled sources, preview bounds, fetch failure, extraction failure, and missing sources;
4. server-level HTTP tests verify source-test response mapping, 404 handling, and blocking-executor offload;
5. black-box integration coverage persists a JSON-configured source, tests it through HTTP, and verifies no collection-run history is created;
6. passthrough REST/HTML and RSS/Atom regression coverage remains green;
7. OpenAPI and owning current-state documentation are synchronized;
8. `./run_checks.sh` passes in the developer environment.

## Implementation tasks

1. Add configuration-driven JSON and HTML extractors behind `SourceItemExtractor`.
2. Preserve passthrough behavior when extraction settings are absent.
3. Add bounded source-test application and HTTP boundaries.
4. Add OpenAPI schemas and operation for source testing.
5. Add focused unit/server/integration coverage.
6. Synchronize current-state documentation and specification lifecycle.
