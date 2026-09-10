---
type: Specification
title: SignalHarvester collection to Kafka transport
description: Current implementation sub-specification for RawItemDiscovered mapping, Protobuf byte serialization, Kafka publication, topic conventions, and round-trip verification.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: verification-pending
---
# SignalHarvester collection to Kafka transport

## Status

Implementation complete — Gradle and Kafka/Testcontainers verification pending before archival.

This slice introduces the first real Kafka producer boundary without yet implementing collection-run orchestration. It deliberately separates event transport mechanics from the later use case that selects enabled sources, owns `collectionRunId`, defines partial-failure semantics, and publishes multiple fetched results.

## Goal

Implement the first asynchronous publication boundary:

```text
FetchedSourceContent
    + explicit publication context
    -> collection-owned RawItemEventPublisher
    -> RawItemDiscovered Protobuf mapping
    -> explicit byte[] serialization
    -> Kafka
```

Generated Protobuf classes must remain at the Kafka adapter boundary and must not become the collection module's application/domain API.

## Relationship to the umbrella specification

This sub-spec advances umbrella requirements R7 and R8 and the active event-contract sub-spec. It establishes real Kafka transport for the first vertical slice while leaving collection-run lifecycle, normalization/deduplication, analysis, and result persistence to later bounded increments.

## Current state

Baseline available before this slice:

- `FetchedSourceContent` preserves source identity, requested URI, raw bytes, content type, and fetch timestamp;
- `EventEnvelope` and `RawItemDiscovered` version-one Protobuf contracts already exist;
- collection HTTP access and bounded fetch coordination are implemented;
- Kafka/Testcontainers dependency aliases exist, but no Kafka adapter is wired into collection.

Implemented in this slice:

- `RawItemEventPublisher` as the collection-owned asynchronous outbound boundary;
- `RawItemPublicationContext` for caller-owned correlation/profile/category/trace metadata;
- `RawItemDiscoveredMapper` at the Kafka/Protobuf adapter boundary;
- generated `eventId` per publication and caller-owned stable `rawItemId`;
- explicit `RawItemDiscovered.toByteArray()` serialization;
- Micronaut `@KafkaClient` publication with String keys and byte-array values;
- configurable versioned raw-item topic naming;
- `rawItemId` as the Kafka record key for item-level partitioning;
- acknowledged/blocking publisher semantics with a collection-owned publication exception;
- unit tests for mapping, charset handling, serialization, key/topic behavior, and failure normalization;
- Kafka Testcontainers producer/consumer round-trip coverage.

## Requirements

### R1 — collection owns its Kafka publication boundary

The collection module owns the API used by later collection orchestration to publish discovered raw content. Callers must not depend directly on Micronaut Kafka clients or generated Protobuf messages.

### R2 — Protobuf remains a transport type

`RawItemDiscovered` and `EventEnvelope` generated Java classes may appear in mapper/Kafka adapter code and transport tests. They must not become collection domain/application models or synchronous cross-module Java APIs.

No handwritten Java DTO may duplicate the complete Kafka wire event merely to avoid using the generated class at the adapter boundary.

### R3 — correlation and provenance are explicit

Publication must preserve:

- configured `sourceId`;
- requested source URL;
- raw response content and content type;
- fetch/discovery timestamp;
- caller-supplied correlation identity;
- caller-supplied monitoring-profile identity;
- caller-supplied information category;
- traceparent when supplied.

The Kafka adapter must not invent collection-run or monitoring-profile semantics that belong to orchestration.

### R4 — event identity and raw-item identity have separate ownership

Each published event gets a new globally unique `eventId`. The caller supplies a non-blank `rawItemId`, because item identity belongs to collection/extraction semantics rather than Kafka transport. Re-publishing the same logical raw item may therefore retain its `rawItemId` while receiving a new event identity.

The exact deterministic fingerprint/external-ID policy belongs to the later collection-run and normalization/deduplication slices.

### R5 — topic and partitioning conventions are explicit

The initial default topic is:

```text
signalharvester.collection.raw-item-discovered.v1
```

The topic name must be runtime configurable. The physical topic name includes the event family and contract version; conceptual future collection lifecycle events do not automatically require separate topics.

The caller-owned `rawItemId` is the initial Kafka key so downstream item processing can partition consistently by item identity. A later change to partitioning semantics requires explicit compatibility/ordering analysis.

### R6 — serialization is explicit

The producer must send the Protobuf message as `byte[]` using Kafka `ByteArraySerializer`; Kafka keys use `StringSerializer`.

Schema Registry is not required for this slice. The `.proto` source remains the authoritative contract.

### R7 — producer acknowledgement and failures are explicit

Publication waits for Kafka acknowledgement at this boundary. Producer configuration uses `acks=all` and enables Kafka producer idempotence.

Kafka/framework exceptions must not leak through the collection-owned publisher API. They are normalized to a collection publication failure carrying the raw-item identity, logical correlation identifier, and targeted topic.

Kafka producer idempotence is transport-level duplicate protection and does not replace application-level replay/idempotency semantics.

### R8 — the Kafka round trip is verified

A Testcontainers test must:

1. start a disposable Kafka broker;
2. create the configured raw-item topic;
3. resolve the real collection publisher through Micronaut;
4. publish one fetched payload;
5. consume the Kafka record with String/byte-array deserializers;
6. parse the bytes as `RawItemDiscovered`;
7. verify topic, key, event identity, correlation, provenance, and payload.

The test must not require a developer-installed Kafka broker or public network service.

## Scenario

Given a fetched source response and explicit flow metadata, the collection publisher creates one version-one raw-item event, serializes it to Protobuf bytes, and sends it to the configured Kafka topic. A consumer can decode those bytes using only the published `.proto` contract.

The current generic HTTP transport maps one fetched response to one initial raw item. Source-specific parsing/extraction may later split a response into multiple raw items; that refinement must preserve this Kafka boundary rather than leaking generated event messages into source adapters.

## Non-goals

This slice does not implement:

- loading enabled sources from `SourceConfigurationProvider`;
- collection-run creation or persistence;
- `collectionRunId` ownership beyond accepting a generic correlation identifier from callers;
- partial-failure semantics for multi-source runs;
- scheduling;
- parsing one HTTP response into multiple source-specific items;
- deterministic deduplication fingerprints;
- analysis consumers;
- retry/DLQ orchestration;
- Schema Registry;
- transactional outbox;
- Event Explorer decoding.

## Design constraints

- Keep the publication API imperative because this is an acknowledged transport operation used by an imperative collection workflow.
- Do not use `CompletableFuture` or reactive types only to wrap Kafka producer work.
- Do not log raw collected content or configured URLs containing sensitive query parameters.
- Keep topic names configurable rather than hardcoding deployment-specific broker topology.
- Preserve `.proto` field numbers and meanings.
- Keep application-level idempotency separate from Kafka producer idempotence.

## Validation

This sub-spec is accepted when:

1. `:modules:collection:test` compiles and all non-container tests pass;
2. the Kafka Testcontainers round-trip passes with Docker available;
3. `:contracts:event-contracts:test` remains green;
4. `:app:test` proves application bean wiring still starts;
5. generated Protobuf Java is not committed;
6. collection application/public types do not expose generated Protobuf or Micronaut Kafka types;
7. current-state docs describe the new producer while leaving collection-run orchestration pending.

## Implementation tasks

1. Restore the real `contracts:event-contracts` dependency to collection.
2. Add Micronaut Kafka support to the collection module.
3. Add configurable topic and producer serialization settings.
4. Add collection-owned publication context/result/failure types.
5. Add the Protobuf mapper and acknowledged Kafka publisher.
6. Add mapping/serialization/failure unit tests.
7. Add the Kafka/Testcontainers round-trip test.
8. Update owning documentation and move current implementation focus to the next collection-run slice after verification.
