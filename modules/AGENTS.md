---
type: Development Guide
title: Functional module development rules
description: Shared rules for ownership, public APIs, dependencies, persistence, adapters, testing, and module-context contracts across functional modules.
---
# Functional module development rules

## Module ownership

Every directory directly under `modules/` is a cohesive functional module. It owns the behavior and infrastructure needed for that capability rather than contributing one technical layer to the whole repository.

A module may contain domain/model code, use cases, persistence, Kafka/HTTP adapters, scheduling, mapping, and tests when those concerns belong to the module.

## Public boundary

Expose the smallest practical Java API for deliberate synchronous module entry points. A published Java API belongs under:

`io.signalharvester.<module>.api`

The `api` package is a semantic boundary, not a collection of every Java interface. Put a type there only when callers outside the implementation should intentionally be allowed to depend on it.

Primary module behavior exposed through the Java API must be represented by interfaces. Concrete application classes implement those interfaces from internal packages; inbound adapters such as REST controllers depend on the interface when a Java application API exists.

Do not create an interface mechanically for every implementation class. Internal ports such as repositories, outbound HTTP clients, publishers, analyzers, normalizers, clocks, or strategy abstractions remain in their owning internal packages unless they are intentionally published module capabilities.

Public API data should be minimal. Reuse an already appropriate immutable/domain value type rather than creating a duplicate DTO only to mirror it under `api`. If several API-specific data types exist and a subpackage improves discoverability, use `api.model`; otherwise keep a small contract surface flat. An API interface may refer to an owned model outside `api` when that avoids artificial duplication, but the module contract must point to the referenced type so selective context loading remains deterministic.

A consumer module must not import another module's private persistence/adapters/implementations or query its tables directly.

Use Kafka/Protobuf when asynchronous integration is the intended system boundary. Do not serialize to Protobuf merely to call another module in the same JVM synchronously.

REST controllers are transport adapters, not Java module APIs. Keep them under the module's HTTP/Web adapter package and do not create controller interfaces merely for structural symmetry. The authoritative REST schema remains under `contracts/api-contracts/`.

Versioned Kafka schemas remain authoritative under `contracts/event-contracts/`; generated Protobuf classes are not module API models.

## Module contract and selective context

Every functional module must keep a concise `contract.md` in the module root.

The contract is a context/navigation index, not a handwritten duplicate of Java/OpenAPI/Protobuf signatures. It should identify:

- purpose and owned responsibilities;
- authoritative synchronous Java API package and primary entry-point interfaces;
- authoritative REST/OpenAPI and event-contract locations when applicable;
- owned data;
- allowed dependencies;
- forbidden access;
- important invariants;
- extension points.

When an API is implemented in Java, `contract.md` should link to the owning package/files instead of copying all methods and fields into Markdown.

For work centered on one module, prefer this context-loading order:

1. read the target module's `contract.md`, local instructions, specs, tests, and implementation as needed;
2. for a collaborating module, read its `contract.md` first;
3. read only that collaborator's `api/**` and any explicitly referenced contract/model files unless the task requires changing or diagnosing its implementation;
4. read OpenAPI/Protobuf sources directly when the interaction crosses REST/Kafka boundaries.

Do not inspect another module's implementation merely because it is available when its published contract is sufficient.

A future dedicated Gradle artifact containing published module contracts may be introduced if independent implementation substitution, extraction, build isolation, or context-loading benefits justify it. Do not centralize all module APIs into a shared module prematurely; the providing functional module owns its contracts today.

## Internal organization

Organize by cohesive behavior/sub-capability first. `api`, `application`, `model`, `persistence`, or adapter-specific packages are allowed when useful, but no ceremonial package tree is required.

Prefer code that remains understandable if the module is later extracted, without prematurely designing every module as a remote service.

## Architecture enforcement

Cross-module production Java dependencies may target only the providing functional module's `api..` package. A module with no synchronous functional-module consumer should normally have no published `api` package; keep HTTP-facing or other local inbound interfaces internal.

Architecture tests must protect the acyclic functional-module graph, published-API purity, the public-API-only cross-module dependency rule, and obvious adapter-boundary violations such as direct HTTP-to-persistence coupling. They must not force internal ports into `api` merely to satisfy a naming convention.

## Testing

Keep module behavior tests with the module. Use deterministic adapters/fakes for unit tests and Testcontainers-based integration tests where real Kafka/PostgreSQL behavior matters.

Put fast module tests in `src/test/java` and container-backed integration tests in `src/integrationTest/java`. The separate Gradle source sets are the lifecycle boundary; do not depend on JUnit tags to prevent an integration test from entering the default `test` task.

Prefer tests and multi-module fixtures to resolve published API interfaces when the purpose is to exercise a module as a consumer would. Tests dedicated to one internal adapter may still use the internal contract directly.

## Documentation

`contract.md` is authoritative for module ownership, published integration surfaces, data ownership, dependency rules, forbidden access, invariants, and extension points. Do not duplicate those sections in the module README beyond a short link or implementation-specific explanation.

Each module README is the developer entry point for current implementation state, runtime/operational behavior, known limitations, and navigation to deeper documentation. Put an explicit link to `contract.md` near the top and again in the "Read next" section.

Add a module `IMPLEMENTATION.md` only when concrete classes/call paths become substantial enough to justify a separate current-state document.
