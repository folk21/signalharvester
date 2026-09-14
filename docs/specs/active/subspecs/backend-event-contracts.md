---
type: Specification
title: SignalHarvester backend Kafka and Protocol Buffers event contracts
description: Active technical sub-specification defining Protobuf ownership, Kafka wire contracts, schema evolution, generated-code boundaries, and testing rules.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: active
---
# SignalHarvester backend Kafka and Protocol Buffers event contracts

## Status

Active supporting technical sub-specification — raw and analysis schemas, generation workflow, collection/analysis Kafka adapters, explicit byte serialization, and Testcontainers round trips exist. Event Explorer decoding of the currently published raw/analyzed/rejected families is implemented. Compatibility fixtures for evolved published schemas and later event families remain pending.

This specification refines the umbrella event-driven requirements and the backend modular-monolith structure. It defines how Kafka integration events are represented without turning Protocol Buffers into a universal internal application model.

## Goal

Use Protocol Buffers where a stable binary wire contract provides concrete value while preserving simple Java module APIs and browser-friendly HTTP contracts.

The initial communication rule is:

```text
in-process module collaboration  -> Java interfaces and Java types
Kafka asynchronous events        -> Protocol Buffers
REST request/response APIs        -> JSON described by OpenAPI
browser live updates via SSE      -> JSON
```

A future separately deployed synchronous service may introduce gRPC/Protobuf only when extraction creates a real network boundary. gRPC is not an initial modular-monolith requirement.

## Source structure

The canonical event-contract sources are:

```text
contracts/event-contracts/
├── build.gradle.kts
└── src/main/proto/
    └── io/signalharvester/events/
        ├── common/v1/
        ├── collection/v1/
        ├── analysis/v1/
        └── results/v1/
```

Generated Java sources belong under Gradle build output and must not be committed or manually edited as authoritative event DTOs.

## Requirements

### R1 — `.proto` is the source of truth

Every Kafka integration-event payload must be defined by a versioned `.proto` schema before producer and consumer implementations depend on it.

No handwritten Java event DTO may silently become a competing wire-contract definition for the same Kafka event.

### R2 — contract packages are versioned

Protocol Buffers package structure must include an explicit contract version, initially `v1`.

Versioning is a compatibility boundary, not a release-number mirror. Additive compatible changes remain in the same contract version. A new package version is introduced only when an intentional incompatible contract is required.

### R3 — common envelope metadata is intentional

A common event envelope or equivalent shared metadata must carry the information required for reliable observation and correlation, including at least:

- event identity;
- event type or unambiguous message identity;
- occurrence timestamp;
- correlation identity;
- trace identity or trace-propagation metadata where appropriate;
- producer/schema metadata required for diagnostics and compatibility.

Envelope design must not force arbitrary domain payloads into opaque untyped byte blobs merely to avoid defining explicit messages.

### R4 — generated Protobuf classes are transport types

Generated classes may be used directly at Kafka producer/consumer adapters and contract mapping boundaries.

They must not become the canonical domain model of `configuration`, `collection`, `analysis`, or `results` merely because they are shareable.

Modules may map between generated messages and module-owned models when this protects module independence or domain semantics.

### R5 — field-number compatibility is mandatory

Published field numbers are stable contract identifiers.

A removed field number must not be reused for a different meaning. Removed field numbers and names should be declared `reserved` where practical.

Existing field semantics must not be changed in place when doing so could cause an older consumer to interpret new data incorrectly.

Additive optional fields are preferred for compatible evolution.

### R6 — unknown-field tolerance is expected

Consumers must be designed with normal Protobuf forward/backward compatibility in mind. An older consumer should tolerate additive fields it does not understand unless explicit validation rules require otherwise.

Business validation must distinguish between an unknown additive field and a genuinely invalid event.

### R7 — Kafka serialization is explicit

Kafka producer and consumer configuration must use an explicit Protobuf serialization strategy.

The first implementation may use direct Protobuf byte serialization without introducing a Schema Registry. Schema Registry integration may be added later when compatibility enforcement, operational schema discovery, or multi-language consumers justify it.

The application-level contract remains the `.proto` schema regardless of whether a registry is present.

### R8 — Event Explorer must remain human-readable

The technical event-observation path must be able to present meaningful decoded event metadata and selected payload fields even though Kafka stores binary Protobuf payloads.

The browser must receive a diagnostic JSON representation from the backend. The browser must not decode Kafka Protobuf records directly.

### R9 — REST and SSE do not inherit Kafka serialization

REST controllers use JSON request/response contracts described through OpenAPI.

SSE streams use JSON event payloads suitable for browser `EventSource` consumers.

A Kafka/Protobuf contract may be mapped into an HTTP/SSE representation, but the generated Protobuf class is not automatically the public browser contract.

### R10 — in-process module calls remain normal Java calls

When one module synchronously invokes another module inside the modular monolith, it uses a narrow Java module API.

Serialization to Protobuf and immediate deserialization inside the same JVM is forbidden unless a concrete test or migration requirement explicitly justifies simulating a remote boundary.

### R11 — contract tests validate schema behavior

Automated tests must cover at least:

- Protobuf generation as part of the Gradle build;
- serialization/deserialization of representative events;
- compatibility-sensitive fixtures for important published schemas;
- Kafka producer/consumer round trips in integration tests;
- correct decoding into Event Explorer representations.

A later CI step should add schema compatibility checks when enough published event versions exist to make such checks meaningful.

## Initial event families

The first vertical slice is expected to require messages conceptually equivalent to:

```text
common/v1
    EventEnvelope

collection/v1
    CollectionRequested
    CollectionStarted
    CollectionCompleted
    CollectionFailed
    RawItemDiscovered

analysis/v1
    ItemAnalyzed
    ItemRejected

results/v1
    ResultAvailable
```

This list defines initial event families, not a requirement that every conceptual event must have its own Kafka topic.

## Non-goals

The initial implementation does not require:

- gRPC between modules inside the monolith;
- Protobuf for browser REST APIs;
- Protobuf for SSE payloads;
- generated Protobuf classes as shared domain entities;
- a Schema Registry from the first vertical slice;
- one topic per Protobuf message;
- dynamic `Any`-based payloads as a substitute for explicit schemas.

## Design constraints

- Prefer explicit messages over generic property bags.
- Keep protocol contracts smaller than internal domain models where possible.
- Avoid putting source-specific raw response structures into stable shared contracts unless downstream processing genuinely requires them.
- Preserve event identity, correlation, provenance, and traceability across serialization boundaries.
- Treat generated Java sources as build artifacts.
- Keep module-specific mapping logic inside the module that owns the semantic conversion.
- Do not reuse removed field numbers.
- Do not introduce gRPC solely to demonstrate another technology.

## Validation

This specification is satisfied for the first vertical slice when:

1. Gradle compiles `.proto` files into Java classes in `contracts:event-contracts`;
2. collection publishes at least one real Kafka event using Protobuf serialization;
3. analysis consumes and decodes that event without a parallel handwritten wire DTO;
4. a Kafka/Testcontainers integration test proves the round trip;
5. the Event Explorer can expose a human-readable JSON view of the event metadata/payload;
6. REST and SSE contracts remain independent JSON-facing contracts;
7. schema-evolution tests demonstrate at least one additive field change without breaking an older fixture/consumer expectation.

## Implementation tasks

1. Define `common/v1/event-envelope.proto`.
2. Define the first `collection/v1/raw-item-discovered.proto` contract.
3. Configure Gradle Protobuf generation in `contracts:event-contracts`.
4. Add Protobuf serializer/deserializer adapters at Kafka producer/consumer boundaries as those boundaries are implemented.
5. Add contract-level serialization tests.
6. Add Kafka/Testcontainers producer-consumer integration tests for implemented event flows.
7. Add Event Explorer decoding/mapping for the first event type.
8. Add compatibility fixtures before evolving the first published schema.
9. Evaluate Schema Registry only after the basic contract workflow is stable.
10. Evaluate gRPC only if a module is later extracted behind a synchronous network boundary.
