---
type: Module Contract
title: SignalHarvester module contract — Security
description: Identity ownership, credential persistence, authentication integration, role invariants, and administrative HTTP boundary.
---
# SignalHarvester module contract — Security

## Purpose

Own application identities, local credential verification, explicit role assignments, and the Security-owned HTTP administration surface used by backend authentication/RBAC.

## Feature ownership

- `SECURITY.IDENTITY_ROLES`
- `SECURITY.AUTHENTICATION`

The application-wide endpoint authorization matrix implements `SECURITY.AUTHORIZATION` in the composition root because it protects HTTP adapters owned by several functional modules.

## Owned responsibilities

- persist user/system identities and role assignments;
- hash local passwords and verify presented credentials;
- enforce baseline human/system role invariants;
- provide the blocking Micronaut authentication provider;
- validate SignalHarvester-specific JWT subject/role claims;
- provide the configured JWT audience for generated tokens;
- expose current-principal and ADMIN-only user-management APIs;
- bootstrap the first administrator from deployment-provided credentials when explicitly configured.

## Public integration surface

### Synchronous Java API

None. Security currently publishes no synchronous cross-module Java API. Other functional modules do not inspect identities or roles directly; HTTP authorization is enforced through Micronaut Security at the external boundary.

### REST / SSE API

Authoritative schema: `contracts/api-contracts/src/main/resources/openapi/signalharvester-v1.yaml`.

Security owns authentication/current-principal routes under `/api/v1/auth` and ADMIN user-management routes under `/api/v1/admin/users`.

The module does not expose password hashes, signing secrets, CSRF signing material, or JWT token contents through its REST DTOs.

### Events

None. Security currently owns no Kafka event boundary.

## Owned data

PostgreSQL schema `security` is owned by migrations under `modules/security/src/main/resources/db/migration/security/`:

- `security.users` — stable identity, username, identity type, enabled state, password hash, timestamps;
- `security.user_roles` — explicit additive role assignments.

There is intentionally no login-session or JWT-session table.

## Dependencies

No synchronous dependency on another functional module is required.

The module depends on Micronaut HTTP/security, Jdbi over Micronaut JDBC transaction infrastructure, Flyway, and PostgreSQL runtime support.

## Forbidden access

Other functional modules must not query or mutate `security` tables directly. Security code must not read another module's private tables to make authorization decisions. Passwords, hashes, JWTs, signing secrets, and CSRF secrets must not be logged.

## Important invariants

- usernames are unique case-insensitively;
- every `HUMAN` identity contains `USER`;
- every `BOT` identity contains `BOT` and need not contain `USER`;
- roles are additive and non-hierarchical;
- generated JWT `sub` is the stable persisted UUID rather than the mutable login name;
- accepted JWT role claims contain only known role values;
- disabled accounts cannot authenticate through local credentials;
- administrative updates cannot disable or remove `ADMIN` from the last enabled administrator;
- issued JWTs are stateless and remain valid until expiry after disablement/role changes;
- local passwords are stored only as salted PBKDF2-HMAC-SHA256 hashes;
- browser JWTs are not exposed to JavaScript or URL query parameters;
- cookie-authenticated mutations require CSRF validation when security is enabled;
- the first administrator has no repository-known default password.

## Extension points

External identity-provider federation, MFA, password reset, self-service registration, refresh/revocation state, machine-specific BOT permissions, and immediate token revocation require separate requirements. Do not introduce a session table or hidden role hierarchy to implement them implicitly.
