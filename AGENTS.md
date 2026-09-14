# AGENTS.md

This file defines repository-wide development rules for coding agents working on SignalHarvester.

Follow it strictly. Read the nearest local `AGENTS.md` before changing a concrete module, contract, test area, or infrastructure area. Local instructions may add stricter rules for their scope, but must not weaken the repository-wide invariants defined here.

Prefer the smallest correct change that satisfies the task.

## Project Intent

SignalHarvester is a Java/Micronaut modular monolith for collecting, analyzing, and presenting data from external sources.

The stable architectural rule is:

> Functional modules own complete capabilities. They communicate only through explicit Java APIs or published event contracts.

The backend is one deployable application initially. A module becomes a separately deployed service only when there is a concrete scaling, isolation, ownership, or deployment reason.

The frontend is a separate project. This repository owns backend behavior and the REST/OpenAPI, SSE, and Kafka contracts it publishes.

## Source of Truth

Use this ownership order:

1. production code, machine-readable contracts, and Flyway migrations define implemented behavior;
2. tests define verified behavior;
3. active specifications define intended changes for work in progress;
4. `docs/ARCHITECTURE.md` defines stable architecture and ownership boundaries;
5. `docs/IMPLEMENTATION.md` explains current wiring, adapters, and implementation details;
6. other owning documents define configuration, installation, usage, testing, and roadmap information;
7. archived specifications and documentation are historical only.

When documentation conflicts with implemented behavior, verify the behavior, fix the owning document, and avoid duplicating the same rule in multiple places.

## Module Boundaries

Each functional Gradle module owns its capability end to end:

- domain/model;
- application/use-case logic;
- persistence;
- external adapters;
- Kafka producers/consumers;
- REST/SSE adapters when the module owns that behavior;
- tests;
- public module API.

Do not organize the repository as global `controllers/`, `services/`, `repositories/`, or `entities/` layers.

`app/` is the composition root. It owns application startup, Micronaut wiring, global runtime configuration, and assembly. Keep business logic out of `app/`.

A module's cross-module Java API belongs under an explicit `api` package. Other modules may depend on that API, but must not import the providing module's persistence, adapter, or implementation packages.

No circular module dependencies.

`common/` is a minimal shared kernel. Put code there only when it is genuinely generic, stable, and owned by no functional module. Business models and capability-specific helpers stay in their owning modules.

Prefer feature/bounded-context packaging inside modules over ceremonial technical layering.

## Code Structure and Design

- Keep the architecture flexible but simple. Do not introduce abstractions, layers, interfaces, or indirection without a concrete reason.
- Apply DRY thoughtfully: remove stable duplication that creates maintenance risk, but prefer limited duplication over premature generalization.
- Prefer composition over inheritance.
- Add abstractions after the boundary or variation is understood, not in anticipation of hypothetical reuse.
- Keep public APIs small and explicit.
- Keep mutable state and resource ownership obvious.
- Keep classes and methods reasonably sized and focused. Avoid very large classes or methods, but do not split code mechanically into tiny types or methods merely to satisfy a pattern or arbitrary size rule.
- A class should normally represent one coherent responsibility, but closely related behavior may remain together when splitting it would reduce readability or increase indirection.
- Preserve unrelated behavior and avoid drive-by refactors.
- Do not introduce hidden conventions that cannot be discovered from the repository.

## Communication Contracts

Use the transport that matches the boundary:

- module → module inside the JVM: Java interfaces and Java types;
- asynchronous communication: Kafka + Protobuf;
- frontend/test clients → backend: REST + JSON described by OpenAPI;
- backend → browser live updates: SSE + JSON.

Do not use Kafka or Protobuf merely to avoid defining a proper in-process Java API.

### Kafka and Protobuf

`.proto` files under `contracts/event-contracts/` are the source of truth for Kafka schemas.

Generated Protobuf Java code is build output:

- never hand-edit it;
- never treat generated messages as domain models;
- map transport messages to module/domain types when useful;
- do not include generated output in source archives unless explicitly requested.

For published event schemas:

- keep explicit versions;
- preserve existing field numbers and semantics;
- never reuse removed field numbers;
- reserve removed fields and names;
- prefer additive compatible changes;
- update compatibility/serialization tests with contract changes.

Kafka flows must preserve event identity, correlation, tracing context, idempotency expectations, retry/DLQ behavior, and replay safety.

### REST and SSE

OpenAPI is the authoritative external REST contract where defined.

When changing an HTTP contract, update the contract source, implementation, and contract/integration tests together.

Do not expose module persistence entities or internal implementation types over REST/SSE. Use explicit DTOs/records at external boundaries.

Map expected application/domain failures through Micronaut `ExceptionHandler` implementations at the HTTP boundary instead of duplicating controller-local `try/catch` translation. Keep handlers non-blocking unless they explicitly offload blocking work.

The browser must not connect directly to Kafka or PostgreSQL.

## Persistence Rules

PostgreSQL is shared infrastructure, but data ownership is module-local.

- A module owns its tables/schema and Flyway migrations.
- Never read or mutate another module's private tables directly.
- Cross-module data access uses the providing module's Java API or an event flow.
- Never edit an already-applied Flyway migration; add a new migration.
- Keep transaction ownership explicit.
- Design consumers and persistence for duplicate delivery.
- Use unique constraints or processed-event state where appropriate.
- Use transactional outbox when database state and Kafka publication must be atomic.

## Java Rules

- Use idiomatic modern Java.
- Prefer immutable records/value objects for DTOs, commands, queries, and small values where appropriate.
- Keep classes and methods reasonably sized and focused, but do not over-fragment code into tiny classes or methods without a real readability, ownership, reuse, or testing benefit.
- Use constructor injection. Do not use field injection.
- Prefer explicit types and contracts over reflection or hidden classpath discovery.
- Keep core/application logic minimally coupled to Micronaut.
- Use `Optional` primarily as a return type, not as a field or parameter.
- Handle nullability explicitly; do not use `null` as an undocumented state.
- Use SLF4J for logging; do not use `System.out`.
- Include correlation/business identifiers in useful logs without logging secrets or unnecessarily large payloads.
- Package names are lowercase; directory/module names are lowercase and follow existing repository naming.
- All code comments, configuration comments, examples, and software documentation are in English.

### Java Documentation and Comments

- Every production Java class, interface, enum, record used as a non-trivial domain/contract type, and other meaningful top-level type must have a short Javadoc comment explaining its purpose and role.
- Class-level Javadoc should normally be concise. Use a longer explanation when the class has non-obvious responsibilities, lifecycle, invariants, threading behavior, transaction semantics, or integration constraints.
- Public or protected methods that form a contract should have Javadoc when their behavior is not obvious from the signature.
- Complex or key methods, including important interface implementations and integration entry points, must have a concise Javadoc comment describing what they do and any important guarantees, side effects, or failure semantics.
- Do not add comments that merely restate the code or method name.
- Inline `//` comments are allowed only for non-obvious or complex logic where the reasoning, invariant, workaround, or algorithm would otherwise be difficult to understand. Test-purpose comments use the Javadoc-style convention defined in the Testing Rules rather than inline comments.
- Prefer making code self-explanatory through naming and structure before adding inline comments.
- Keep comments synchronized with behavior; outdated comments are defects.

## Configuration and External I/O

Configuration belongs in typed configuration or persisted configuration, not hardcoded implementation values.

Do not hardcode secrets, external URLs, schedules, environment-specific paths, provider choices, or behavior that should be configurable.

Collection code must remain testable without the public network.

Prefer a simple imperative model for blocking application workflows. On the Java 21 baseline, run blocking HTTP/JDBC/application work on Micronaut's `TaskExecutors.BLOCKING`, which uses Virtual Threads when available. Never perform blocking work on a Netty event-loop thread.

For REST controllers that invoke blocking application logic, use `@ExecuteOn(TaskExecutors.BLOCKING)` at the method or class level as appropriate. Do not apply blocking execution mechanically to streaming/reactive endpoints such as SSE; keep true streams on `Publisher`/reactive boundaries.

Micronaut HTTP clients may expose a synchronous module-facing boundary when calls execute only from a blocking/Virtual-Thread context. Use declarative `@Client` for stable service/base-URI integrations; use the Micronaut-managed low-level client for configuration-driven absolute URLs that may span arbitrary hosts. Client filters should stay lightweight and non-blocking; a filter that performs blocking work must explicitly offload it rather than block the event loop.

Do not hold `synchronized` monitors or other contended locks across blocking HTTP/JDBC calls. This is especially important on the Java 21 Virtual Thread baseline, where pinned carrier threads can reduce scalability.

Use Jakarta Validation for configuration and external/input boundaries where Micronaut owns validation. Keep intrinsic domain/value-object invariants enforced by the owning Java type so they remain valid outside the Micronaut container.

External calls must have explicit timeout and concurrency behavior. Add retries only when their semantics are understood and bounded.

## Dependencies

Keep the dependency surface small.

Before adding a library, check whether the JDK, Micronaut, or an existing dependency already solves the problem clearly.

Do not add or upgrade dependencies, plugins, database extensions, or infrastructure components unless the task explicitly requires it or the user approves it.

Do not introduce a framework-wide abstraction to solve one local problem.

## Testing Rules

Every behavioral or contract change requires appropriate tests.

Use the smallest useful layer first:

- unit tests for domain/application logic;
- module/component tests for module behavior and adapters;
- Testcontainers integration tests for PostgreSQL/Kafka behavior;
- cross-module/end-to-end tests under `testing/integration-tests`;
- ArchUnit tests for important module-boundary rules.

Use JUnit 5, Micronaut test support, and deterministic fake HTTP sources. When blocking REST controllers are introduced, include a server-level test that verifies their blocking work is offloaded from the Netty event loop.

Tests must not depend on:

- public network access;
- live external websites;
- production credentials;
- external SaaS;
- nondeterministic timing when a controllable clock/source can be used.

Never disable or weaken a failing test merely to make the build green.

Keep tests readable as executable specifications:

- Give every test class a concise but informative class-level Javadoc that names the behavior/boundary it protects and links to the primary tested production type with `{@link ...}` when practical. Reference an existing stable feature/spec identifier when it improves navigation; never invent ad-hoc feature codes.
- Put a concise Javadoc-style purpose comment (`/** ... */`, normally one line, at most two) immediately before every test method annotation. Describe only the scenario/guarantee; do not prefix it with labels such as `Test purpose:`.
- Name opaque or semantically meaningful fixture values (IDs, hashes, correlation values, profile/source identifiers, fixed timestamps) with constants when the name makes the scenario clearer, especially when reused. Do not extract trivial one-off literals mechanically.
- Prefer parameterized JUnit tests when the same behavior/assertions are exercised over a list of input values; do not use parameterization to hide materially different scenarios.
- Apply DRY inside tests: extract repeated fixture construction/setup into focused helpers when it improves readability. Promote helpers to shared test-support only when multiple owners genuinely share the same semantics.

Validation commands and the current command matrix are owned by `docs/TESTS.md`; quality-tool policy and report ownership are in `docs/QUALITY.md`. Use the Gradle wrapper when it exists. Run focused tests first, then broader validation appropriate to the change. `./run_checks.sh` is the canonical routine repository gate for default checks, container-backed integration tests, and FULL-archive reproducibility. `./run_rare_checks.sh` is the broader periodic gate for coverage, static analysis, dependency analysis, and project-size metrics; it runs `run_checks.sh` first.

When adding a new repository-wide test, static-analysis rule, coverage gate, or other verification step, wire it into the canonical verification workflow that owns it. Routine checks belong in `run_checks.sh`; periodic or expensive quality checks belong in `run_rare_checks.sh`. Update `docs/TESTS.md` or `docs/QUALITY.md` when the command matrix or quality workflow changes.

Never claim a test/build passed unless it was actually run successfully.

## Documentation Ownership

Each topic should have one authoritative owner:

| Topic | Owner |
|---|---|
| Repository overview and quick start | `README.md` |
| Agent development rules | `AGENTS.md` |
| Stable architecture and module boundaries | `docs/ARCHITECTURE.md` |
| Current implementation/wiring | `docs/IMPLEMENTATION.md` |
| Backend configuration | `docs/CONFIGURATION.md` |
| Backend installation | `docs/INSTALLATION.md` |
| Backend usage/operations | `docs/USAGE.md` |
| Test strategy and commands | `docs/TESTS.md` |
| Coverage and code-quality tooling | `docs/QUALITY.md` |
| Future work | `docs/ROADMAP.md` |
| Completed project changes | `CHANGELOG.md` |
| Specification workflow/navigation | `docs/specs/README.md` |
| Intended significant changes | active specifications |
| REST schema | `contracts/api-contracts/` |
| Kafka schema | `contracts/event-contracts/src/main/proto/` |

Do not create a second document that restates an existing owner's content. Link to the owning document instead.

Specifications describe intended changes, not permanent current-state architecture. After implementation, move stable knowledge into the owning documentation and archive the completed specification.

Keep specification lifecycle state unambiguous:

- a spec exists in exactly one lifecycle location (`active/` or `archive/`), never both;
- archival is a move, not a duplicated copy;
- umbrella `current_focus`, `docs/specs/README.md`, and the active spec tree must agree;
- an implemented-but-unverified slice may remain active with `verification-pending`, but accepted behavior belongs in current-state documentation rather than being maintained twice in the spec.

Do not update broad documentation or CHANGELOG for trivial local refactors that do not change behavior, contracts, architecture, validation workflow, or contributor-facing knowledge.

CHANGELOG entries begin with the date in `YYYY-MM-DD` format; time is omitted.

### Documentation Writing Style

Repository documentation is working context for both developers and coding agents. Optimize it for precise interpretation and efficient retrieval rather than stylistic formality.

- Use clear, direct, modern English. Prefer simple sentence structure over bureaucratic or academic prose.
- Preserve technical precision. Simplify the language, not the architecture, contracts, invariants, or domain model.
- Use stable terminology. Use the same term for the same concept throughout the project. Do not introduce synonyms only for stylistic variety.
- Avoid noun piles, unnecessary jargon, and dense sentences that contain several independent assertions.
- Prefer short sentences with one primary assertion. Keep a condition and its consequence together when separating them would make the rule less precise.
- State ownership, dependency direction, invariants, ordering, failure semantics, and compatibility rules explicitly when they affect implementation.
- Use normative words consistently in specifications: `must`, `must not`, `should`, and `may`.
- Separate processing sequences from logical rules. Describe pipelines as ordered steps. Describe invariants, validation rules, and failure semantics separately.
- Use bullets and small sections when they make requirements easier to scan and retrieve.
- Clearly distinguish current implemented behavior from technical debt, planned work, and other future enhancements. Never describe planned behavior as if it already exists.
- Prefer concrete references to modules, APIs, schemas, events, configuration keys, and persistence owners over abstract descriptions.
- Keep important requirement identifiers and established domain terms stable across specifications, documentation, tests, and code.

## Always / Ask First / Never

### Always

- Read this file and the nearest applicable local `AGENTS.md`.
- Inspect existing code/tests in the owning module before introducing a new pattern.
- Stay inside the requested module/scope unless the task requires a cross-module change.
- Respect public module APIs and data ownership.
- Make the smallest coherent change.
- Update tests for behavioral/contract changes.
- Run and report relevant validation.
- Report changed files, deleted files, and anything not verified.

### Ask First, Unless the Task Explicitly Authorizes It

- Add or upgrade dependencies/plugins.
- Introduce a new functional module.
- Change public cross-module Java APIs.
- Make incompatible REST, SSE, or Protobuf contract changes.
- Change module dependency direction or ownership.
- Put new code into `common/`.
- Introduce new cross-cutting infrastructure or a new persistence technology.
- Split a module into a separately deployed service.
- Perform a broad refactor affecting unrelated modules.

### Never

- Create circular module dependencies.
- Reach into another module's internal implementation or private tables.
- Hand-edit generated Protobuf sources.
- Reuse removed Protobuf field numbers.
- Expose persistence entities/internal types across module or HTTP boundaries.
- Commit secrets, real credentials, or local `.env` values.
- Hardcode developer-machine-specific paths or environment behavior.
- Disable tests to hide regressions.
- Invent requirements, APIs, migrations, benchmark numbers, or test results.
- Perform unrelated cleanup while completing a focused task.

## Working Style

1. Identify the owning module and applicable local instructions.
2. Inspect existing implementation, contracts, tests, and active specification before editing.
3. State the intended change scope when the task is substantial.
4. Reuse existing patterns when they are sound; do not clone a bad pattern merely for consistency.
5. Implement the smallest correct change.
6. Run focused validation; run architectural/integration checks when boundaries or contracts changed.
7. Update only the owning documentation.
8. Report what changed, what was validated, what remains unverified, and an appropriate commit message.

## Progressive Disclosure

Read detailed guidance only when relevant:

- `docs/ARCHITECTURE.md` — system/module boundaries and dependency direction;
- `docs/IMPLEMENTATION.md` — current implementation and wiring;
- `docs/TESTS.md` — test layers and validation commands;
- `docs/QUALITY.md` — coverage, static-analysis, dependency-analysis, and quality-gate policy;
- `docs/specs/README.md` — specification workflow;
- `modules/AGENTS.md` — functional module rules;
- `contracts/AGENTS.md` — contract rules;
- `contracts/event-contracts/AGENTS.md` — Protobuf/Kafka evolution rules;
- `testing/AGENTS.md` — testing-specific rules;
- `infra/AGENTS.md` — infrastructure/observability rules;
- `app/AGENTS.md` — composition-root rules;
- `common/AGENTS.md` — shared-kernel restrictions.

When in doubt, preserve existing contracts and module boundaries, choose the smaller change, and do not invent missing requirements.