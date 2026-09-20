---
type: Specification
title: SignalHarvester backend module contract discoverability and repository navigation
description: Bounded backend hardening slice that makes module boundaries, published contracts, dependencies, and authoritative source locations deterministic to discover without requiring unrelated implementation context.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: verification-pending
---
# SignalHarvester backend module contract discoverability and repository navigation

## Status

Verification-pending implementation focus.

Stages 1-3 are implemented. Stage 1 audited the repository contract model, Stage 2 normalized all six functional-module contracts, and Stage 3 reconciled global/current-state navigation plus stale active-spec inventories. The slice remains active until all relevant repository tests and validations pass in an environment with the required Docker and Gradle dependencies.

This specification hardens the existing modular-monolith documentation and contract-navigation model.

It does not introduce new product behavior or runtime semantics. The work is limited to auditing and normalizing repository documentation, module contracts, and navigation between authoritative sources.

The resulting repository structure must remain useful to ordinary developers, code-review tooling, architecture analysis, and other consumers that need to discover module boundaries and contracts efficiently.

## Feature scope

This work refines existing capabilities:

- `PLATFORM.MODULAR_MONOLITH` — functional-module ownership, dependency direction, and published Java boundaries;
- `CONTRACTS.HTTP` — discoverability of authoritative REST/OpenAPI contracts;
- `CONTRACTS.KAFKA_PROTOBUF` — discoverability of authoritative Kafka/Protobuf contracts.

No new product feature ID is introduced.

## Goal

Make the SignalHarvester repository sufficiently self-describing that a developer can determine, with minimal ambiguity and by reading the smallest practical amount of repository content:

- which functional module owns a capability;
- which synchronous Java surface, if any, that module deliberately publishes;
- which authoritative source files define that published Java surface;
- where the module's REST/SSE contracts are defined;
- which Kafka/Protobuf messages the module consumes or produces;
- which data the module owns;
- which other functional modules it may depend on synchronously;
- which integration boundaries are asynchronous;
- which implementation areas are private;
- which invariants a consumer must understand without reading private implementation;
- where to navigate next when implementation detail is actually required.

The preferred navigation path must expose sufficient contract information without requiring broad inspection of unrelated implementation.

## Current state

The repository already has the main architectural foundation required by this work.

Every current functional module has a root `contract.md`, and module documentation treats that file as the authoritative compact ownership and integration map.

The shared module rules already require a module contract to identify:

- purpose and owned responsibilities;
- authoritative synchronous Java API;
- REST/OpenAPI and event-contract locations;
- owned data;
- dependencies;
- forbidden access;
- important invariants;
- extension points.

The same rules already define selective navigation for a collaborating module: read its contract first, then its published `api/**` and explicitly referenced contract/model files, and inspect private implementation only when the task requires changing or diagnosing that implementation.

Configuration is currently the only functional module that publishes a synchronous cross-module Java API. Its contract explicitly identifies the published package, entry-point interfaces, and contract data types. Other modules currently expose their cross-module behavior primarily through REST/SSE or Kafka contracts rather than synchronous Java APIs.

The remaining problem is not absence of module contracts but consistency and deterministic discoverability:

- authoritative contract references are not uniformly expressed at the same level of precision;
- some contracts may require broader source inspection than is necessary to understand their published boundary;
- active/global documentation may retain repository inventories or current-state statements that become stale as modules are added or capabilities move;
- the repository has not yet been systematically reviewed to confirm that a consumer can understand each published boundary without inspecting unrelated private implementation;
- duplicated boundary descriptions may make it harder to identify which source is authoritative.

## Requirements

### R1 — repository documentation remains software-focused

Repository documentation must describe SignalHarvester architecture, behavior, ownership, contracts, implementation state, development rules, and validation requirements.

Documentation introduced or modified by this work must avoid introducing concepts, terminology, or process requirements that are not part of the SignalHarvester software-development lifecycle itself.

Repository contracts and navigation structures should remain generally useful regardless of which development, analysis, or automation tools consume them.

### R2 — every functional module has an authoritative boundary contract

Every functional module directly under `modules/` must have a root `contract.md`.

The contract must remain the authoritative compact source for:

- purpose;
- owned responsibilities;
- public integration surfaces;
- owned data;
- allowed dependencies;
- forbidden access;
- important invariants;
- extension points.

Module README files must describe implementation/runtime state and navigate to the contract rather than duplicate the contract's ownership and boundary definitions.

Module-specific sections are allowed when they add useful information, but common boundary information must remain predictably discoverable.

### R3 — synchronous Java boundaries are explicit

For every module, the `Synchronous Java API` section must explicitly state one of:

1. the module publishes no synchronous cross-module Java API; or
2. the authoritative package and published entry points.

When a published Java API exists, the contract must identify:

- the authoritative package path;
- primary entry-point interfaces;
- published API data types required to use those interfaces.

The contract must reference authoritative source locations rather than copy method signatures or field definitions.

If a deliberately published interface refers to an owned contract/model type outside `api/**`, that type must be explicitly identified by repository-relative source location so that the published boundary can be understood without scanning unrelated module implementation.

Internal repositories, adapters, strategies, controller-facing ports, and implementation interfaces must not be promoted into the public API merely to improve discoverability.

### R4 — REST and SSE ownership is discoverable with minimal contract reading

When a module owns REST or SSE behavior, its contract must identify the authoritative OpenAPI source.

The navigation information should allow a developer to reach the relevant module-owned contract area without requiring broad inspection of unrelated API definitions.

Exact operation paths or operation identifiers are not required when the authoritative OpenAPI source and surrounding contract organization already make the owned surface sufficiently clear.

More precise references may be used when they materially reduce ambiguity or the amount of contract content that must be inspected.

The module contract must not duplicate complete request/response schemas.

Generated client/server artifacts are not authoritative contract sources.

When a module owns no REST/SSE surface, that absence should be explicit when ambiguity would otherwise exist.

### R5 — Kafka/Protobuf boundaries are discoverable with minimal contract reading

When a module consumes or produces Kafka events, its contract must identify:

- consumed message types;
- produced message types;
- authoritative Protobuf contract source locations sufficient to find those messages efficiently.

Where an envelope or shared event contract is required to understand the boundary, that dependency must also be discoverable.

The preferred reference should point to the smallest practical authoritative source set needed to understand the boundary.

Generated Protobuf Java classes are transport output and must not be treated as authoritative source contracts.

When a module currently publishes or consumes no event contract, that absence should be explicit when relevant.

### R6 — functional-module dependencies are consumer-readable

Each module contract must describe synchronous functional-module dependencies explicitly.

For each synchronous dependency, the contract must make clear:

- the providing module;
- the published API boundary used;
- why the dependency is permitted.

Asynchronous relationships must be described as event/transport boundaries rather than Java module dependencies.

A consumer must not need another module's persistence, HTTP adapter, Kafka adapter, or internal application implementation to understand an allowed dependency.

### R7 — owned data and forbidden access remain explicit

Each module contract must identify module-owned durable data at the level necessary to protect ownership boundaries.

The contract must make clear that other functional modules may not read or mutate private module tables directly unless an explicit architecture change establishes another ownership model.

Persistence implementation details that are irrelevant to consumers should remain outside the compact contract.

### R8 — consumer-relevant invariants are captured at the boundary

A module contract must contain invariants that another module or external adapter must know to use the published boundary correctly.

Examples may include:

- identity scope;
- retry/idempotency expectations;
- transaction ownership when externally observable;
- lease/ownership semantics when they constrain callers or downstream consumers;
- ordering or cursor semantics;
- compatibility behavior;
- externally meaningful authorization constraints.

The contract must not become a full implementation narrative.

An invariant belongs in `contract.md` when violating it from outside the module could produce incorrect behavior even while all Java, HTTP, or event signatures remain valid.

### R9 — authoritative navigation does not duplicate contract definitions

Contract documents should prefer repository-relative Markdown links or equally unambiguous repository-relative paths to authoritative files.

They must not reproduce:

- Java method signatures;
- OpenAPI schema definitions;
- Protobuf message definitions;
- SQL schema definitions;

when those definitions already have an authoritative source.

The objective is to make the authoritative information easy to locate while keeping the amount of material that must be read as small as practical.

### R10 — module index remains complete and current

`modules/README.md` must enumerate every current functional module and link to its developer README and boundary contract.

Repository architecture documentation must identify the current module topology without requiring readers to infer it from historical specifications.

When a new functional module is introduced, its contract/navigation entry points are part of the module's definition of done.

### R11 — active supporting specifications must not act as stale implementation inventories

Active supporting specifications may define unresolved architectural guardrails but must not retain misleading current-state repository inventories.

If an active supporting specification contains an older illustrative module list or repository tree that is no longer the authoritative current topology, it must either:

- be updated where the list remains normatively relevant; or
- be replaced with a reference to current-state architecture/module documentation.

Historical accepted behavior must not be copied forward merely to keep an old inventory visually complete.

### R12 — global current-state documentation remains consistent with module contracts

The following current-state documents must be reviewed for contradictions with authoritative module contracts and current repository topology:

- root `README.md`;
- `docs/ARCHITECTURE.md`;
- `docs/IMPLEMENTATION.md`;
- `docs/FEATURES.md`;
- `docs/ROADMAP.md` where it describes current accepted state;
- `modules/README.md`;
- owning module READMEs.

The review should remove or narrow duplicated boundary statements rather than introduce more copies of the same facts.

Companion-repository implementation state should not be asserted as backend current-state truth unless that statement is necessary for a backend-owned contract or deployment requirement and can be kept authoritative.

### R13 — public API purity remains an architecture property

This work must preserve the existing rule that production cross-module Java dependencies target only deliberately published `api..` packages.

No new public interface or model may be introduced solely to make repository navigation easier.

If the discoverability audit finds that another module currently requires private implementation knowledge to use a published capability safely, the preferred correction order is:

1. improve the boundary contract;
2. improve navigation to existing authoritative contract types;
3. only then change the software API if there is an actual architecture defect.

Any material Java API change discovered by this audit requires separate design treatment and must not be hidden inside documentation cleanup.

### R14 — minimum sufficient context is the navigation objective

Repository navigation should optimize for the smallest practical set of authoritative sources that still provides a complete understanding of the relevant software boundary.

For a collaborating module, the expected minimum should normally be:

- its `contract.md`;
- its published Java API sources, when any;
- explicitly referenced published contract/model types;
- relevant authoritative OpenAPI or Protobuf sources.

Private implementation should not be required merely to discover:

- ownership;
- published capabilities;
- allowed dependencies;
- integration contracts;
- consumer-relevant invariants.

Private implementation remains appropriate when diagnosing defects, changing behavior, or understanding internal runtime mechanics.

The audit should prefer reducing unnecessary reading over adding more duplicated explanatory documentation.

## Acceptance scenarios

### A1 — synchronous dependency discovery

Given a developer needs to understand how Collection reads Configuration,

when the developer reads:

- `modules/collection/contract.md`;
- `modules/configuration/contract.md`;
- Configuration's published Java API sources;

then the developer can identify the supported synchronous integration without reading Configuration persistence, HTTP, or application implementation.

### A2 — event dependency discovery

Given a developer needs to understand Analysis input and output contracts,

when the developer reads:

- `modules/analysis/contract.md`;
- the referenced authoritative event-contract sources;

then the consumed and produced Protobuf messages and their integration semantics are discoverable without reading Analysis Kafka adapter implementation.

### A3 — REST ownership discovery

Given a developer needs to understand the Results browser-facing API,

when the developer reads:

- `modules/results/contract.md`;
- the referenced authoritative OpenAPI source;

then the relevant Results REST/SSE contract can be located and understood without scanning unrelated module controllers or broad unrelated API definitions.

### A4 — module with no synchronous API

Given a module intentionally has no synchronous functional-module consumer,

its contract explicitly says that no synchronous Java API is published and does not expose internal interfaces merely for navigational convenience.

### A5 — module topology discovery

Given root architecture/navigation documentation and module contracts,

a developer can enumerate every functional module and find the authoritative boundary document for each one.

### A6 — selective consumer understanding

For every functional module, a reviewer can determine the module's externally relevant ownership, integration surfaces, dependencies, forbidden access, and consumer-relevant invariants from:

- its `contract.md`;
- deliberately published Java API sources, when any;
- authoritative OpenAPI/Protobuf sources, when applicable;

without requiring private implementation solely to discover the boundary.

Private implementation may still be required to diagnose defects, modify behavior, or understand internal runtime mechanics.

### A7 — minimal contract-reading path

For representative cross-module and transport integrations, a reviewer can identify a bounded set of authoritative files that provides sufficient contract understanding without first reading the full implementation of either participating module.

The selected sources should contain enough information to avoid relying on undocumented assumptions while avoiding unrelated implementation detail.

### A8 — current-state consistency

A review of root documentation, module indexes, module contracts, and active supporting specifications reveals no contradictory current module ownership, published-boundary, or repository-topology descriptions.

## Non-goals

This specification does not:

- introduce new runtime or product behavior;
- create a new repository packaging mechanism;
- introduce a second architecture-description model alongside the existing source code and documentation;
- require machine-readable duplication of module contracts;
- make every internal Java interface public;
- centralize module APIs into a shared contract module;
- create separate deployable services;
- change REST, SSE, Kafka, persistence, security, or runtime semantics solely for documentation convenience;
- duplicate OpenAPI, Protobuf, Java, or SQL definitions in Markdown;
- require exact OpenAPI operation-level references when a broader authoritative source already provides efficient and unambiguous navigation;
- introduce new validation infrastructure as part of this bounded slice;
- modify the companion frontend repository.

## Design constraints

The existing modular-monolith ownership model remains authoritative.

`contract.md` is a navigation and semantic-boundary document, not a generated source dump.

The providing functional module continues to own its Java contract.

REST remains authoritative in `contracts/api-contracts/`.

Kafka event schemas remain authoritative in `contracts/event-contracts/`.

Current-state documentation owns accepted implementation state.

Active specifications own unresolved intended change only.

The preferred documentation structure should reduce the amount of repository content required to understand a software boundary rather than increasing duplication.

Any discovered runtime, API, persistence, security, or reliability defect that requires material behavioral change should be split into an appropriate bounded implementation change rather than silently fixed as part of this documentation/readiness slice.

## Validation

This slice relies on review plus the repository's existing tests and validation mechanisms.

Validation should include:

1. review every functional-module `contract.md` against the requirements above;
2. verify the functional-module index is complete;
3. verify published Java API descriptions against actual source packages;
4. verify REST/SSE references against the authoritative OpenAPI source;
5. verify Kafka event references against authoritative Protobuf sources;
6. verify module dependency statements against Gradle dependencies and existing architecture constraints;
7. review representative module boundaries using only the intended minimal navigation path;
8. review root/global documentation for contradictory or stale ownership and topology statements;
9. run all relevant existing tests and validations.

No new automated contract-readiness validation is required by this specification.

If the audit demonstrates a recurring structural failure that is both important and inexpensive to detect automatically, such validation may be proposed as a separate follow-up stage.

## Implementation tasks

### Stage 1 — repository contract audit

Inventory every functional module and its current integration boundaries.

For each module:

- inspect `contract.md`;
- compare it with actual published Java APIs;
- identify owned REST/SSE contracts;
- identify consumed and produced Kafka/Protobuf contracts;
- identify synchronous functional-module dependencies;
- review owned-data and forbidden-access statements;
- identify consumer-relevant invariants;
- determine whether the published boundary can be understood without unrelated private implementation.

Also review:

- `modules/README.md`;
- root architecture/current-state documentation;
- active supporting specifications that describe module topology or boundaries.

Record inconsistencies before normalizing documentation so that broad changes are not made without evidence.

This stage must not change runtime behavior.

### Stage 2 — module contract normalization

Normalize functional-module contracts based on the audit.

The normalized contracts should make the following predictable and easy to locate:

- ownership and purpose;
- synchronous Java API presence or absence;
- published Java source locations;
- REST/SSE authoritative sources;
- Kafka/Protobuf message boundaries;
- owned data;
- allowed dependencies;
- forbidden access;
- important consumer-facing invariants;
- extension points.

Prefer the smallest authoritative source references sufficient to understand the boundary.

Do not duplicate complete Java, OpenAPI, Protobuf, or SQL definitions.

Update module READMEs only where necessary to restore the intended separation between boundary documentation and implementation/runtime documentation.

### Stage 3 — global documentation normalization

Reconcile global current-state documentation with the normalized module contracts.

In particular:

- ensure `modules/README.md` reflects the complete functional-module topology;
- remove or narrow duplicated ownership descriptions;
- update stale current-state statements;
- replace obsolete implementation inventories in active supporting specifications with references to authoritative current-state documentation where appropriate;
- preserve historical information only where it still serves an active specification purpose.

This stage remains documentation/navigation hardening and must not introduce new runtime behavior or new validation infrastructure.

## Stage 1 audit findings

The audit covered all six functional modules, their root contracts and developer READMEs, actual published Java packages, module Gradle dependencies, module-owned HTTP controllers, the authoritative OpenAPI document, current Protobuf sources, `modules/README.md`, root/current-state architecture documentation, and the active supporting specifications.

### Confirmed baseline

- All six functional modules have both `contract.md` and `README.md`.
- `modules/README.md`, root `README.md`, `docs/ARCHITECTURE.md`, and `settings.gradle.kts` recognize the current six-module topology: Configuration, Collection, Analysis, Results, Event Observation, and Security.
- Configuration is the only functional module with a published synchronous Java `api` package. The contract lists both provider interfaces and all currently published API data types; the published types are self-contained inside `configuration.api`.
- Production cross-module Java imports found by the audit are Collection-to-Configuration imports under `io.signalharvester.configuration.api..`; no private cross-module production import was found.
- All module READMEs already link to their authoritative `contract.md` and generally keep the intended developer-overview role.
- The authoritative HTTP contract is one OpenAPI source file: `contracts/api-contracts/src/main/resources/openapi/signalharvester-v1.yaml`.
- The current event-contract source set is small and explicit: the common envelope, `RawItemDiscovered`, `ItemAnalyzed`, `ItemRejected`, and `DeadLetterEvent` Protobuf files.

### F1 — REST/SSE navigation precision is inconsistent

Configuration, Collection, and Analysis currently refer only to the broad `contracts/api-contracts/` directory from their module contracts, while Results, Event Observation, and Security reference the authoritative OpenAPI file directly.

The broad directory reference is sufficient to discover that an HTTP contract exists, but it requires unnecessary navigation and is inconsistent with the minimum-sufficient-context goal.

Stage 2 should normalize all HTTP-owning modules to the authoritative OpenAPI file and add only enough route-family information to locate the module-owned surface efficiently. Full OpenAPI operation duplication is not required.

### F2 — several module-owned administrative HTTP surfaces are missing from contract summaries

Analysis owns both bounded item-inspection endpoints and controlled Analysis dead-letter inspection/replay endpoints, but its REST section currently names only the schema directory and implementation package.

Results lists its public result browse/detail/live endpoints but omits its controlled Results dead-letter inspection/replay endpoints.

Event Observation lists event history/live and Processing Flow endpoints but omits its controlled Event Observation dead-letter inspection/replay endpoints.

These are real module-owned REST boundaries already present in the authoritative OpenAPI contract. Stage 2 should make the route families discoverable without duplicating request/response schemas.

### F3 — event-contract navigation is broader and less complete than necessary

Collection and Analysis reference the entire Protobuf source root. Event Observation also references the entire root without naming the exact event families it consumes. Results narrows its normal input to `analysis/v1/`, but still does not identify the terminal failure contract it can publish.

The event-contract README confirms that Analysis, Results, and Event Observation publish `DeadLetterEvent` during terminal handling. Their module `Events` sections currently focus on normal pipeline events and do not expose that failure-event boundary directly.

Stage 2 should normalize event sections around the exact message types and smallest practical authoritative `.proto` source set, including `DeadLetterEvent` where the module publishes it and the common envelope when required to understand the boundary.

### F4 — common contract structure is close but not fully uniform

All module contracts expose the main ownership, integration, data, dependency, forbidden-access, invariant, and extension information. The section structure is therefore already strong enough for selective navigation.

Minor inconsistencies remain:

- Security has no explicit `Events` subsection stating that it currently owns no Kafka event boundary;
- Event Observation uses `REST / SSE API` while other modules use `REST API` even where SSE is included;
- Configuration's owned-responsibility summary mentions source-management REST implementation but does not equally name its Monitoring Profile REST implementation even though the module owns both controllers.

Stage 2 should normalize these items only where doing so improves predictable navigation; module-specific sections such as Security feature ownership may remain.

### F5 — dependency discoverability is correct but can be more focused

Collection's contract correctly declares Configuration as its only synchronous functional-module dependency and correctly restricts access to `configuration.api..`.

The actual production imports use the two Configuration provider interfaces plus Configuration-owned API data types. Stage 2 should reference the primary provider interfaces in Collection's dependency description so a reader can start with the smallest useful source set rather than scanning the whole package blindly.

No new Java API is required by the audit.

### F6 — some source locations are descriptive rather than repository-relative

Results and Security describe migration sources using shortened paths such as `db/migration/...` or a migration filename without the full module resource path. Similar shorthand is useful in implementation prose but is less deterministic for contract navigation than a repository-relative path or link.

Stage 2 should normalize source-location references when the shorter form creates avoidable lookup work. It should not turn module contracts into migration inventories.

### F7 — the active project-structure supporting specification contains stale implementation inventories

`backend-project-structure.md` explicitly says that accepted current architecture belongs in current-state documentation and that the supporting specification must not maintain a second implementation inventory.

Despite that rule, the document still contains historical/current-looking inventories that predate the Security module, including the illustrated repository tree, the initial Gradle-project list, the approximate dependency graph, the service-to-module replacement list, and the implementation task checklist.

The current authoritative repository topology includes `modules:security`; root/current-state documentation and `settings.gradle.kts` already reflect it.

Stage 3 should remove or narrow obsolete inventories in the active supporting specification and reference current-state architecture/navigation documents where appropriate, while preserving still-normative boundary rules.

### F8 — current-state module navigation is otherwise consistent

The audit found no missing functional module in `modules/README.md`, root `README.md`, `docs/ARCHITECTURE.md`, or `settings.gradle.kts`.

`docs/IMPLEMENTATION.md` has dedicated current-state sections for Configuration, Collection, Analysis, Results, Event Observation, and Security and links back to owning module documentation for the primary functional modules.

This means Stage 3 should be a normalization/reduction pass rather than a rewrite of the global documentation model.

### F9 — cross-repository current-state claims require careful ownership

Backend current-state documents contain statements about completion conditions that depend on the companion frontend repository, including the real frontend image requirement for full platform Kubernetes acceptance.

The backend repository alone cannot establish whether companion-repository implementation state has advanced. Stage 3 should review these statements against the specification's ownership rule and either retain them as backend-owned deployment requirements or narrow wording that attempts to describe companion-repository current state.

The audit does not classify those statements as stale without an authoritative companion-repository baseline.

### Stage 1 conclusion

The audit found no reason to change runtime behavior, module dependency direction, or published Java APIs as part of this slice.

The existing architecture is suitable for minimum-context navigation once the documented boundaries are normalized. Stage 2 can therefore remain a documentation-only module-contract pass focused on all six `contract.md` files, with module README changes only where contract-versus-implementation ownership requires them.

## Stage 2 normalization outcome

Stage 2 normalized all six functional-module contracts without changing runtime behavior, published Java APIs, transport schemas, or module dependency direction.

The normalized contracts now provide a predictable minimum-context navigation path:

- every module has explicit `Synchronous Java API`, `REST / SSE API`, and `Events` subsections under `Public integration surface`;
- every HTTP-owning module points directly to `contracts/api-contracts/src/main/resources/openapi/signalharvester-v1.yaml` and names only the route families needed to locate its owned surface efficiently;
- Configuration points directly to its repository-relative published Java API package and keeps all published contract data within that package;
- Collection identifies `SourceConfigurationProvider` and `MonitoringProfileConfigurationProvider` as its primary synchronous Configuration entry points instead of requiring an initial scan of the full package;
- Collection, Analysis, Results, and Event Observation enumerate the smallest practical authoritative Protobuf source set for the events they consume or produce, including the shared envelope for normal pipeline events and `DeadLetterEvent` for terminal consumer failures where applicable;
- Configuration and Security explicitly state that they currently own no Kafka event boundary;
- module-owned migration locations use repository-relative resource paths where persistence navigation is useful;
- Analysis, Results, and Event Observation expose their existing controlled dead-letter HTTP route families in their boundary summaries.

No module README required modification: all six already delegate ownership and integration boundaries to their root `contract.md`. No implementation source, test, OpenAPI, Protobuf, Gradle dependency, or persistence migration was changed.

Stage 3 remains responsible for global/current-state documentation normalization and stale inventories in active supporting specifications.

## Stage 3 normalization outcome

Stage 3 reconciled global/current-state navigation without changing runtime behavior, published APIs, OpenAPI, Protobuf schemas, persistence, or module dependencies.

The normalization made the following ownership boundaries explicit:

- `backend-project-structure.md` no longer maintains a duplicated repository tree, functional-module inventory, Gradle-project list, event-schema inventory, persistence-schema inventory, approximate dependency graph, historical service-to-module list, or implementation checklist; current topology is delegated to root/module navigation, module contracts, `settings.gradle.kts`, and current-state architecture documentation;
- the active umbrella no longer records companion-frontend implementation items as backend-owned pending state; frontend implementation sequencing and current status remain companion-repository concerns, while cross-repository acceptance requirements stay normative in the umbrella;
- backend `README.md`, `docs/ARCHITECTURE.md`, `docs/IMPLEMENTATION.md`, and `docs/ROADMAP.md` now describe only backend-owned deployment acceptance and the independently owned frontend boundary rather than asserting the companion repository's current completion state;
- `docs/ROADMAP.md` no longer duplicates a companion-frontend next-stage plan;
- root current-state documentation continues to own the complete current functional-module topology, while `modules/README.md` remains the authoritative functional-module navigation index.

No change was required in `docs/FEATURES.md` or `modules/README.md`: their ownership vocabulary and current module index were already consistent with the normalized contracts.

The documentation-hardening work is implemented. Final acceptance and archival remain blocked only on completion of all relevant repository validation in a suitable environment.

## Completion criteria

This slice is complete when:

- every functional module has a reviewed authoritative `contract.md`;
- common boundary information is predictably discoverable across modules;
- published Java boundaries can be located without private implementation search;
- REST/SSE and Kafka contract sources can be reached through a small and sufficient set of authoritative references;
- functional-module topology and ownership documentation are internally consistent;
- active supporting specifications no longer provide misleading stale implementation inventories;
- representative collaborating-module scenarios can be understood from module contracts, published APIs, and authoritative transport contracts without unnecessary implementation reading;
- no new API or runtime behavior was introduced merely to improve documentation navigation;
- no new repository-specific validation mechanism was introduced as part of this bounded slice;
- all relevant existing tests and validations pass.
