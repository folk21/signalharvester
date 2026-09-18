---
type: Specification
title: Production-oriented Results browsing
description: Add backward-compatible keyset pagination and indexed text search to the Results REST browsing contract.
document_role: subspec
spec_status: completed
parent: ../spec-signal-harvester-platform.md
---
# Production-oriented Results browsing

## Status

Accepted on 2026-09-18 after the canonical repository gate passed. The Results REST array response remains compatible while the browsing boundary provides opaque keyset pagination, bounded text search, and supporting PostgreSQL indexes.

## Feature scope

- `RESULTS.BROWSING` — production-oriented result list navigation and search.
- `CONTRACTS.HTTP` — additive query/header evolution of the public Results REST boundary.

## Goal

Make the existing Results feed usable beyond one bounded recent-result window without breaking the accepted frontend contract.

The stage must provide deterministic keyset pagination and indexed user-facing text search while preserving Results ownership and the current REST/SSE separation.

## Current state

`GET /api/v1/results` currently returns a newest-first bounded JSON array with a `limit` and structured filters. The ordering is deterministic by `analyzedAt DESC`, then `monitoringProfileId`, then `normalizedItemId`.

The endpoint has no continuation cursor and no text search. The Results schema has an `analyzed_at` index but no composite browsing-order index or full-text search index.

The deployed frontend already consumes the endpoint as `ResultSummary[]`. Replacing that body with a page wrapper would be an incompatible REST change and is not required for this refinement.

## Requirements

### R1 — preserve the existing response body

`GET /api/v1/results` must continue to return a JSON array of `ResultSummary`. Existing requests that omit the new parameters must retain their current semantics.

Pagination metadata is additive through the `X-Next-Cursor` response header. The header is present only when another page exists.

### R2 — opaque keyset cursor

The endpoint must accept an optional opaque `cursor` query parameter.

The cursor must represent the last returned deterministic sort key rather than an SQL offset. It must be versioned and safe for use in a URL query parameter.

The cursor must be bound to the structured filters, time range, and search expression that produced it. Reusing it with materially different query criteria must return HTTP 400 instead of silently producing an unrelated page.

Changing only `limit` between pages is allowed.

### R3 — deterministic page ordering

Pages must use the existing total order:

1. `analyzedAt DESC`;
2. `monitoringProfileId ASC`;
3. `normalizedItemId ASC`.

The next page starts strictly after the cursor sort key. The implementation must fetch at most `limit + 1` rows to detect whether another page exists.

The API does not promise a cross-request database snapshot. Results are mutable current projections, so concurrent updates may move a logical Result relative to a previously issued cursor. Clients should merge/de-duplicate by `(monitoringProfileId, normalizedItemId)` just as live delivery already does.

### R4 — bounded text search

The endpoint must accept an optional non-blank `search` expression with a bounded length.

Search uses PostgreSQL full-text search with the explicit `simple` text-search configuration over persisted `title` and `normalizedContent`. `websearch_to_tsquery` semantics provide user-facing token, quoted-phrase, exclusion, and `OR` behavior without requiring a custom query parser.

Search does not change score/classification semantics and does not search another module's tables.

### R5 — persistence support

A new Results-owned Flyway migration must add indexes that support:

- deterministic browse ordering;
- full-text search over the owned analyzed projection.

Applied migrations must not be edited.

### R6 — HTTP validation and compatibility

Malformed, unsupported-version, or criteria-mismatched cursors must return HTTP 400.

The existing `limit` bound remains `1..200`. Search and cursor inputs must have explicit HTTP/application bounds so the endpoint cannot receive unbounded query strings.

For credentialed cross-origin deployments, `X-Next-Cursor` must be exposed through the backend CORS configuration.

### R7 — SSE remains separate

`GET /api/v1/results/stream` keeps its existing filtering/cursor semantics. Browsing `search`, page cursors, and `X-Next-Cursor` are REST concerns and must not be mixed with the durable SSE `Last-Event-ID` cursor.

## Non-goals

- changing the Results response body into a page wrapper;
- OFFSET-based pagination;
- relevance-ranked search results;
- fuzzy/trigram search;
- cross-module joins for profile/source display names;
- search over Event Observation or Analysis tables;
- changing live Results SSE semantics;
- frontend implementation.

## Validation

The stage is ready for acceptance when automated verification proves:

1. existing unpaginated/filtering requests remain wire-compatible;
2. multi-page traversal is deterministic and does not repeat rows in a stable dataset;
3. ties on `analyzedAt` are paginated by profile/item identity correctly;
4. the next-cursor header is absent on the final page;
5. a cursor remains valid when only `limit` changes;
6. malformed and criteria-mismatched cursors return HTTP 400;
7. text search matches title/content terms and excludes non-matching Results;
8. search composes with structured filters and pagination;
9. the new Results indexes are created through Flyway;
10. `./run_checks.sh` passes.

## Implementation tasks

1. Add the versioned cursor codec and paged Results application outcome.
2. Extend the JDBC Results query with keyset continuation and full-text search.
3. Add the Results Flyway indexes.
4. Extend the REST/OpenAPI contract additively with `search`, `cursor`, and `X-Next-Cursor`.
5. Add controller, application, and PostgreSQL regression coverage.
6. Update Results/current-state documentation and run the canonical repository gate.
