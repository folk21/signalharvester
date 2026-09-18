---
type: Module Overview
title: SignalHarvester API contracts
description: Entry point for REST/JSON contract sources and OpenAPI ownership.
---
# SignalHarvester API contracts

## Ownership

`api-contracts` owns source definitions for backend REST/JSON APIs consumed by the companion UI and test clients.

OpenAPI sources belong under `src/main/resources/openapi/`.

## Current state

The initial OpenAPI 3.1 contract is [`src/main/resources/openapi/signalharvester-v1.yaml`](src/main/resources/openapi/signalharvester-v1.yaml).

It currently defines:

- CRUD operations and schemas for configurable external sources;
- persisted-source diagnostic testing with bounded extraction previews and explicit fetch/extraction outcomes;
- manual collection-run execution plus bounded durable run-history reads;
- read-only inspection of analysis-owned normalized-item/deduplication state;
- backward-compatible public Results array browsing with bounded filters, indexed text search, opaque keyset continuation through `X-Next-Cursor`, and profile-scoped result-detail reads;
- resumable Results `text/event-stream` delivery with numeric SSE cursors and `Last-Event-ID` reconnection;
- bounded technical event-observation history with event/correlation/run/item/trace filters;
- resumable technical event-observation SSE with decoded JSON payload metadata for Event Explorer clients.
- reconstructed collection-run and run-scoped item processing-flow graphs with explicit evidence levels.
- authentication/login/logout/current-principal contracts plus ADMIN-only persisted identity and role administration, including `409 Conflict` when an update would remove the last enabled ADMIN capability;
- ADMIN-only owner-specific dead-letter inspection/replay for Analysis, Results, and Event Observation, with explicit dead-letter-id confirmation and sanitized metadata that never exposes serialized source payload bytes.

Source names must contain at least one non-whitespace character, and source locations require an absolute HTTP(S) URL with a host and without embedded credentials or fragments so the external contract matches the configuration-module invariant. Source-test responses keep nullable diagnostic fields explicit and never expose the full fetched response body. `SourceUpsertRequest.enabled` is optional and defaults to `false`; omitted `settings` default to an empty object. Response DTOs explicitly preserve schema-required fields even when collections are empty or nullable operational values are unavailable.

The corresponding REST adapters are implemented in their owning functional modules; this OpenAPI document remains the authoritative external schema. Server-level module tests verify request validation, status mapping, default values, required response shape, blocking execution for synchronous endpoints, and streaming behavior for Results and Event Observation SSE.

REST contracts remain independent from generated Kafka/Protobuf transport classes.
