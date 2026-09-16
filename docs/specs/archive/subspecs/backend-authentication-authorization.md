---
type: Specification
title: SignalHarvester backend authentication and authorization
description: Stateless JWT authentication, persisted identities with additive roles, backend-enforced RBAC, and browser/SSE credential transport.
document_role: subspec
parent: ../spec-signal-harvester-platform.md
spec_status: completed
---
# SignalHarvester backend authentication and authorization

## Status

Accepted after developer verification on 2026-09-16.

This is the current backend implementation focus for `SECURITY.IDENTITY_ROLES`, `SECURITY.AUTHENTICATION`, and `SECURITY.AUTHORIZATION`. The accepted `OBSERVABILITY.APPLICATION` stage precedes it; production-style Kubernetes/infrastructure observability follows after acceptance.

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

The implementation now provides:

- a dedicated `modules/security` functional module with security-owned PostgreSQL identity/role persistence;
- `HUMAN` and `BOT` identity types with additive `USER`, `VIEWER`, `ADMIN`, and `BOT` roles;
- salted PBKDF2-HMAC-SHA256 local password hashes with an externalized work factor;
- blocking PostgreSQL username/password authentication through Micronaut Security;
- short-lived signed JWTs with UUID `sub`, explicit role claims, issuer/audience validation, and SignalHarvester-specific subject/role validation;
- HttpOnly JWT cookie transport for browser REST/native SSE plus bearer-token validation for approved non-browser clients;
- signed double-submit CSRF protection and explicit credentialed CORS configuration;
- ADMIN-only identity administration and deployment-provided first-ADMIN bootstrap;
- backend-enforced role policy for existing Results/admin/diagnostic endpoints.

The default local environment intentionally remains the previous trusted-environment mode until `signalharvester-web` is migrated. Protected HTTP boundaries are activated with the Micronaut `security` environment. No production signing, CSRF, or administrator credential has a repository default.

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

Disabling an account must prevent future authentication. Already issued short-lived JWTs remain valid until expiry after account disablement or role removal; changes apply to newly issued credentials.

The implementation uses short access-token lifetime and stateless validation rather than a persistent revocation/session store. If immediate revocation is required later, that requirement must be added explicitly rather than introducing a hidden session table.

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

Administrative updates must not leave the deployment without an enabled `ADMIN`. Disabling the last enabled administrator or removing its `ADMIN` role must be rejected as a conflict. The check must remain correct under concurrent administrative updates rather than relying on a frontend warning.

### A9 — audit-safe security logging

Feature: `SECURITY.AUTHENTICATION`, `SECURITY.AUTHORIZATION`.

Authentication and authorization outcomes must be observable without logging secrets or complete JWTs.

Useful security logs should include stable principal identity where known, request/correlation context, and denial reason at an appropriate level. Passwords, password hashes, signing keys, bearer/cookie token contents, and sensitive source credentials must never be logged.

### A10 — VIEWER contract remains presentation-independent

Feature: `SECURITY.AUTHORIZATION`, `PRESENTATION.VIEWER_RESULTS`.

The backend must authorize `VIEWER` access by API capability, not by knowledge of React routes or components. Existing Results REST/SSE contracts should be reused where they already satisfy the consumer-facing experience.

The future frontend `VIEWER` screen must hide internal operational detail by presentation and authorization boundary, but this backend slice does not define its layout or UX.

## Endpoint/role matrix

| Boundary | Required access |
|---|---|
| `POST /api/v1/auth/login` | anonymous |
| `POST /api/v1/auth/logout` | authenticated; browser cookie requests use the CSRF policy |
| `GET /api/v1/auth/me` | authenticated |
| `GET /api/v1/results` | `VIEWER` |
| `GET /api/v1/results/{normalizedItemId}` | `VIEWER` |
| `GET /api/v1/results/stream` | `VIEWER` |
| `/api/v1/admin/users/**` | `ADMIN` |
| all other current `/api/v1/**` configuration/admin/diagnostic boundaries | `ADMIN` |
| `/health`, `/health/**`, `/prometheus` | anonymous operational access in this local stage |

`ADMIN` does not imply `VIEWER`. A `BOT`-only principal may authenticate and inspect `/api/v1/auth/me`, but no machine-oriented business endpoint is granted to `BOT` by this initial slice.

## Scenarios

### S1 — human viewer login

An enabled human account with roles `USER` and `VIEWER` authenticates successfully. The browser receives a valid JWT credential without creating a PostgreSQL login session.

The principal can list/inspect Results and receive Results SSE, but receives an authorization failure when requesting an admin-only configuration or diagnostic endpoint.

### S2 — administrator with viewer access

An account explicitly assigned `USER`, `VIEWER`, and `ADMIN` can use both the consumer result APIs and authorized administrative APIs. Removing `VIEWER` does not rely on `ADMIN` implicitly restoring it.

### S3 — bot principal

A system account with `BOT` but no `USER` can authenticate and inspect its own principal contract. The initial endpoint matrix grants no machine-oriented business capability to `BOT`; such access must be added explicitly for a concrete future use case. It does not automatically gain viewer/admin browser capabilities.

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
- Identity/password/authentication behavior is owned by the dedicated `modules/security` functional module; the cross-module HTTP role matrix remains composition-root configuration because it protects endpoints owned by several modules.
- JWT claims must remain small and explicit. Do not embed large user profiles or mutable application data.
- Do not expose password hashes, complete JWTs, or signing material through logs, REST responses, SSE payloads, Event Observation, or Processing Flow.
- Existing Results/Event SSE cursor and reconnect semantics must remain independent from authentication state.
- Authorization tests must exercise the HTTP boundary; frontend tests must not be the only evidence for access control.

## Compatibility / migration

Existing deployments continue to use trusted-environment unauthenticated behavior unless the `security` Micronaut environment is activated. This compatibility mode is temporary and must not be treated as internet-safe.

The protected profile uses the `security` PostgreSQL schema, deployment-supplied JWT/CSRF secrets, optional deployment-supplied first-ADMIN credentials, and the endpoint/role matrix above. The companion frontend must be migrated to authenticated API/SSE access before security becomes the normal shared-deployment mode.

No migration or profile contains a committed default password or signing secret.

## Validation

Acceptance requires at least:

1. persistence tests for user identity, default `USER`, explicit additional roles, disabled users, and system `BOT` identities;
2. unit/contract tests for JWT creation and validation including signature, expiry, issuer, audience, subject, and roles;
3. server-level tests for login, logout/credential clearing, and current-principal APIs;
4. server-level authorization tests covering the explicit endpoint/role matrix, including forbidden admin access for a `VIEWER`, baseline `USER`/`BOT` restrictions, and anonymous operational endpoints;
5. server-level SSE authentication coverage proving native Results SSE authentication without JWT query parameters;
6. CSRF/CORS tests matching the selected browser deployment model, including rejection of an unconfigured credentialed origin;
7. account-update coverage proving disablement/role changes affect newly issued credentials while existing JWTs remain stateless until expiry;
8. concurrent-safe persistence/HTTP coverage proving the last enabled `ADMIN` cannot be disabled or demoted;
9. cross-module/integration coverage proving authorized Results access and rejected unauthorized admin access through the running application;
10. canonical `./run_checks.sh` passing in the developer environment.

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
