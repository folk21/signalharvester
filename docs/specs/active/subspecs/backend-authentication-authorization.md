---
type: Specification
title: SignalHarvester backend authentication and authorization
description: Stateless JWT authentication, persisted identities with additive roles, backend-enforced RBAC, and browser/SSE credential transport.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: active
---
# SignalHarvester backend authentication and authorization

## Status

Active supporting specification for the later security implementation stage.

It is not the current implementation focus. The next planned backend implementation slice is `OBSERVABILITY.APPLICATION`; authentication/authorization follows it before production-style Kubernetes/system acceptance.

## Feature scope

- `SECURITY.IDENTITY_ROLES` — persisted identities and explicit additive roles.
- `SECURITY.AUTHENTICATION` — stateless signed-JWT authentication.
- `SECURITY.AUTHORIZATION` — backend-enforced access control for REST/SSE capabilities.
- `PRESENTATION.VIEWER_RESULTS` — frontend-owned consumer experience that depends on the accepted backend security contract.

Feature identifiers are defined in [`../../../FEATURES.md`](../../../FEATURES.md).

## Goal

Add a small, explicit security model before SignalHarvester is treated as a shared or publicly exposed application.

The backend must:

- persist identities and role assignments;
- authenticate requests with signed JWT credentials;
- remain stateless with respect to login sessions;
- enforce authorization independently of frontend route visibility;
- support both REST and native Server-Sent Events browser boundaries.

## Relationship to the umbrella specification

This slice implements umbrella R31 and the backend-owned portion of R32. It also closes the authentication/authorization part of the trusted-environment limitation that currently applies to the companion frontend.

Detailed `VIEWER` UI behavior remains owned by `signalharvester-web`. When frontend development resumes, that repository must add a bounded UI specification that consumes the accepted authentication/RBAC contract rather than inventing a frontend-only access model.

## Current state

The backend currently exposes configuration, collection operations, Results, Event Observation, and Processing Flow endpoints without authentication. The companion frontend is therefore still a trusted-environment application.

Missing security boundaries are explicit:

- no persisted user account model;
- no application-owned JWT issuer/validator;
- no backend RBAC policy;
- no authentication boundary for REST or SSE.

## Requirement map

| Requirement | Feature ID | Purpose |
|---|---|---|
| A1 | `SECURITY.IDENTITY_ROLES` | Persisted identities and additive roles |
| A2 | `SECURITY.AUTHENTICATION` | Stateless JWT validation |
| A3 | `SECURITY.AUTHENTICATION` | REST/native-SSE credential transport |
| A4 | `SECURITY.AUTHENTICATION`, `SECURITY.AUTHORIZATION` | CSRF and CORS policy |
| A5 | `SECURITY.AUTHORIZATION` | Backend-enforced endpoint access |
| A6 | `SECURITY.IDENTITY_ROLES`, `SECURITY.AUTHENTICATION` | Disablement and role-change semantics |
| A7 | `SECURITY.AUTHENTICATION`, `CONTRACTS.HTTP` | Browser-facing auth API |
| A8 | `SECURITY.IDENTITY_ROLES`, `SECURITY.AUTHORIZATION` | User administration and bootstrap |
| A9 | `SECURITY.AUTHENTICATION`, `SECURITY.AUTHORIZATION` | Audit-safe security logging |
| A10 | `SECURITY.AUTHORIZATION`, `PRESENTATION.VIEWER_RESULTS` | Presentation-independent VIEWER contract |

## Requirements

### A1 — persisted identities and explicit roles

Feature: `SECURITY.IDENTITY_ROLES`.

The backend must persist application identities with at least:

- stable user identity;
- unique login identity suitable for the selected local authentication flow;
- enabled/disabled state;
- credential material stored only in an appropriate non-reversible representation when local passwords are used;
- an explicit set of assigned roles.

A newly created human user must receive `USER` by default. Additional roles are assigned independently. The initial role vocabulary is:

- `USER`;
- `VIEWER`;
- `ADMIN`;
- `BOT`.

Roles are additive. The backend must not implement a hidden role hierarchy in which `ADMIN` automatically implies `VIEWER` or `USER`. An administrator who needs viewer access must have that role explicitly.

A non-human identity may have `BOT` without `USER`.

### A2 — stateless signed JWT authentication

Feature: `SECURITY.AUTHENTICATION`.

Successful authentication must produce a signed, time-bounded JWT that contains the stable principal identity and explicit assigned roles needed for authorization.

The backend must not persist login sessions or JWT session records in PostgreSQL. Each request is authenticated from the presented credential plus persistent account state required by the chosen validation policy.

JWT validation must reject at least:

- invalid signatures;
- expired tokens;
- tokens with unexpected issuer or audience;
- malformed or missing subject identity;
- malformed role claims.

Token lifetime and signing-key configuration must be externalized. Signing secrets/private keys must not be committed to source control.

### A3 — browser transport supports REST and native SSE

Feature: `SECURITY.AUTHENTICATION`.

The browser authentication design must work for ordinary REST requests and the existing native `EventSource` SSE clients.

JWT credentials must not be placed in query parameters or other URLs.

For the browser application, the preferred initial transport is a secure HttpOnly cookie carrying the JWT so the browser can authenticate both REST and same-origin SSE without JavaScript reading the credential. Cookie scope, `SameSite`, `Secure`, expiry, and local-development behavior must be explicit configuration.

If a different transport is selected during implementation, it must preserve native SSE compatibility without exposing credentials in URLs and must be documented in this specification before code changes proceed.

### A4 — CSRF and cross-origin policy

Feature: `SECURITY.AUTHENTICATION`, `SECURITY.AUTHORIZATION`.

If browser authentication uses cookies, state-changing requests must have an explicit CSRF defense appropriate to the deployment model. SameSite policy alone must not be treated as an undocumented substitute for the chosen CSRF model.

The backend must define an explicit CORS policy for separately hosted frontend builds. Wildcard credentialed CORS is not acceptable. The preferred production deployment should keep the browser frontend and backend behind one controlled application origin unless deployment requirements justify otherwise.

### A5 — backend-enforced authorization

Feature: `SECURITY.AUTHORIZATION`.

Authorization must be enforced at backend HTTP/SSE boundaries. Frontend route hiding is only a user-experience concern.

The initial policy must preserve these semantics:

- `VIEWER` may read the Results endpoints and subscribe to Results SSE required by the consumer-facing result experience;
- `ADMIN` may use configuration, source testing, monitoring-profile management, collection operations, analysis inspection, Event Observation, Processing Flow, and other administrative/diagnostic endpoints allowed by the final endpoint matrix;
- `BOT` access must be explicitly granted per machine-oriented endpoint/use case rather than inheriting human UI access;
- baseline `USER` alone authenticates a human identity but does not silently grant `VIEWER` or `ADMIN` capabilities.

The implementation stage must maintain an explicit endpoint/role matrix in this specification or the owning API documentation before authorization annotations/rules are considered complete.

### A6 — account disablement and role changes

Feature: `SECURITY.IDENTITY_ROLES`, `SECURITY.AUTHENTICATION`.

Disabling an account must prevent future authentication. The implementation must define how already issued short-lived JWTs behave after account disablement or role removal.

The initial design should prefer short access-token lifetime and simple stateless validation over a persistent revocation/session store. If immediate revocation is required, that requirement must be added explicitly rather than introducing a hidden session table.

### A7 — authentication API surface

Feature: `SECURITY.AUTHENTICATION`, `CONTRACTS.HTTP`.

The backend must expose an explicit, versioned browser-facing authentication contract sufficient for at least:

- login;
- logout/credential clearing at the browser boundary;
- reading the current authenticated principal and roles.

The implementation stage must update the authoritative OpenAPI contract together with server-level contract tests. Password reset, self-service registration, MFA, and external identity-provider federation are not required by this initial slice.

### A8 — user administration

Feature: `SECURITY.IDENTITY_ROLES`, `SECURITY.AUTHORIZATION`.

User creation, enable/disable state, and role assignment must be manageable through an authenticated administrative boundary. The initial implementation may expose this through backend APIs before a dedicated frontend screen exists.

Administrative APIs must not return password hashes, signing material, or JWT credentials.

The initial bootstrap mechanism for the first `ADMIN` account must be deterministic and deployment-safe. It must not require committing default credentials to the repository.

### A9 — audit-safe security logging

Feature: `SECURITY.AUTHENTICATION`, `SECURITY.AUTHORIZATION`.

Authentication and authorization outcomes must be observable without logging secrets or complete JWTs.

Useful security logs should include stable principal identity where known, request/correlation context, and denial reason at an appropriate level. Passwords, password hashes, signing keys, bearer/cookie token contents, and sensitive source credentials must never be logged.

### A10 — VIEWER contract remains presentation-independent

Feature: `SECURITY.AUTHORIZATION`, `PRESENTATION.VIEWER_RESULTS`.

The backend must authorize `VIEWER` access by API capability, not by knowledge of React routes or components. Existing Results REST/SSE contracts should be reused where they already satisfy the consumer-facing experience.

The future frontend `VIEWER` screen must hide internal operational detail by presentation and authorization boundary, but this backend slice does not define its layout or UX.

## Scenarios

### S1 — human viewer login

An enabled human account with roles `USER` and `VIEWER` authenticates successfully. The browser receives a valid JWT credential without creating a PostgreSQL login session.

The principal can list/inspect Results and receive Results SSE, but receives an authorization failure when requesting an admin-only configuration or diagnostic endpoint.

### S2 — administrator with viewer access

An account explicitly assigned `USER`, `VIEWER`, and `ADMIN` can use both the consumer result APIs and authorized administrative APIs. Removing `VIEWER` does not rely on `ADMIN` implicitly restoring it.

### S3 — bot principal

A system account with `BOT` but no `USER` can authenticate through the machine-oriented contract approved for it. It does not automatically gain viewer/admin browser capabilities.

### S4 — SSE credential continuity

An authenticated browser opens the Results SSE endpoint with native `EventSource`. Authentication succeeds without placing the JWT in the URL and reconnect behavior continues to use the existing SSE cursor contract.

### S5 — invalid or expired JWT

A request with an invalid signature, expired token, or wrong issuer/audience is rejected as unauthenticated before protected application behavior executes.

### S6 — CSRF-protected admin mutation

When browser JWT transport uses cookies, a state-changing administrative request that lacks the required CSRF proof is rejected even when the browser carries a valid authentication cookie.

## Non-goals

This slice does not require:

- self-service public registration;
- organizations or multi-tenancy;
- subscription/billing concepts;
- OAuth/OIDC federation with an external identity provider;
- MFA;
- persistent server-side login sessions;
- refresh-token persistence;
- a frontend user-management screen;
- the detailed `VIEWER` result-feed UI;
- per-object/row-level authorization beyond the initial role/capability model.

## Design constraints

- Authentication/authorization is a backend security boundary owned by an explicit functional capability; it must not be scattered as ad-hoc controller conditionals.
- The final module placement must preserve the repository's functional-module and dependency-direction rules. Introducing a new functional module or new dependency requires the approvals/rationale required by `AGENTS.md` when implementation begins.
- JWT claims must remain small and explicit. Do not embed large user profiles or mutable application data.
- Do not expose password hashes, complete JWTs, or signing material through logs, REST responses, SSE payloads, Event Observation, or Processing Flow.
- Existing Results/Event SSE cursor and reconnect semantics must remain independent from authentication state.
- Authorization tests must exercise the HTTP boundary; frontend tests must not be the only evidence for access control.

## Compatibility / migration

Existing deployments currently behave as trusted-environment installations with unauthenticated APIs. Enabling this slice changes the HTTP security boundary and therefore requires an explicit migration/development profile rather than silently locking out existing local workflows.

The implementation must define:

- schema migrations for users and role assignments;
- first-admin bootstrap behavior;
- JWT signing/verification configuration;
- local-development authentication defaults;
- the point at which unauthenticated compatibility is removed or restricted to an explicitly unsafe development profile;
- the corresponding frontend migration to authenticated API/SSE access.

No migration may add committed default passwords or signing secrets.

## Validation

Acceptance requires at least:

1. persistence tests for user identity, default `USER`, explicit additional roles, disabled users, and system `BOT` identities;
2. unit/contract tests for JWT creation and validation including signature, expiry, issuer, audience, subject, and roles;
3. server-level tests for login, logout/credential clearing, and current-principal APIs;
4. server-level authorization tests covering the explicit endpoint/role matrix, including forbidden admin access for a `VIEWER`;
5. server-level SSE authentication coverage proving native Results SSE authentication without JWT query parameters;
6. CSRF/CORS tests matching the selected browser deployment model;
7. cross-module/integration coverage proving authorized Results access and rejected unauthorized admin access through the running application;
8. canonical `./run_checks.sh` passing in the developer environment.

Frontend VIEWER UX acceptance belongs to the later `signalharvester-web` sub-specification and is not part of this backend slice.

## Implementation tasks

1. Resolve the owning backend capability/module and approved dependencies without violating the modular-monolith rules.
2. Add user/role persistence migrations and application APIs.
3. Define first-admin bootstrap and local-development behavior.
4. Add JWT signing/validation configuration and authentication flow.
5. Add browser credential transport, logout behavior, CSRF policy, and CORS policy.
6. Define and enforce the endpoint/role authorization matrix.
7. Update OpenAPI and server-level contract tests.
8. Add integration coverage and security-safe observability.
9. Update owning current-state documentation only after developer acceptance.
