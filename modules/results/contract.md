---
type: Module Contract
title: SignalHarvester module contract — Results
description: Results persistence/read ownership, asynchronous integration surface, public REST boundary, invariants, and extension rules.
---
# SignalHarvester module contract — Results

## Purpose

Own durable user-facing analyzed-result projections and their read boundary, while retaining terminal rejection state for reliability/operations.

## Owned responsibilities

- consume terminal `ItemAnalyzed` and `ItemRejected` integration events;
- map transport events into Results-owned models;
- materialize analyzed results idempotently;
- retain rejected source-event outcomes without creating duplicate rows on redelivery;
- own Results JDBC transactions and PostgreSQL schema;
- expose bounded read-only REST browsing/detail access over analyzed results.

## Public integration surface

### Synchronous Java API

None. Results currently has no synchronous functional-module consumer, so no `api/` package is published.

### REST API

The authoritative contract is `contracts/api-contracts/src/main/resources/openapi/signalharvester-v1.yaml`:

- `GET /api/v1/results` — compact newest-first analyzed-result feed with bounded filters;
- `GET /api/v1/results/{normalizedItemId}?monitoringProfileId=...` — detailed profile-scoped analyzed result.

The REST surface exposes Results-owned response DTOs only. It does not expose JDBC rows, persistence adapters, or generated Protobuf classes.

### Events

Consumes versioned `ItemAnalyzed` and `ItemRejected` messages from `contracts/event-contracts/src/main/proto/io/signalharvester/events/analysis/v1/`.

Generated Protobuf classes remain inside the Kafka adapter boundary.

## Owned data

PostgreSQL schema `results`, created by `db/migration/results/V4__create_result_projections.sql`:

- `results.analyzed_items` — one current projection per monitoring-profile/logical-item identity;
- `results.analyzed_item_attributes` — normalized result attributes;
- `results.analyzed_item_tags` — ordered analysis tags;
- `results.rejected_items` — terminal rejection records keyed by upstream source-event identity.

Other modules must not query or mutate these tables directly.

## Dependencies

No synchronous functional-module dependency is required. Results depends only on shared infrastructure/framework libraries and the versioned event-contract artifact.

## Forbidden access

Do not import Analysis implementation/application/persistence types or read the `analysis` schema. Do not expose JDBC rows or generated Protobuf classes as REST/module API models.

## Important invariants

- analyzed result identity is `(monitoringProfileId, normalizedItemId)`;
- rejection idempotency identity is `sourceEventId`;
- repeated terminal publication must not create duplicate logical result rows;
- Kafka offsets are committed only after the Results transaction commits successfully;
- malformed payloads, key mismatches, and persistence failures leave the consumed offset uncommitted;
- write and read repositories participate in application-owned JDBC transactions;
- result-feed limit is bounded to `1..200` and ordered deterministically newest first;
- feed retrieval must avoid per-result N+1 persistence reads;
- detailed result lookup is profile-scoped because normalized identity is profile-scoped;
- Results does not provide distributed exactly-once processing; it achieves retry safety through idempotent projection keys.

## Extension points

Add SSE/live result delivery over the existing Results-owned read model when required. Add rejected-result operational inspection only for a concrete consumer. Create a published Java `api/` package only if a real synchronous cross-module consumer appears.
