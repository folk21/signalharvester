---
type: Module Contract
title: SignalHarvester module contract — Security
description: Identity ownership, credential persistence, authentication integration, role invariants, and administrative HTTP boundary.
---
# SignalHarvester module contract — Security

## Purpose

Own application identities, local credential verification, explicit role assignments, and the security-owned HTTP administration surface used by backend authentication/RBAC.

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

None. Other functional modules do not inspect identities or roles directly. HTTP authorization is enforced through Micronaut Security at the external boundary.

### REST API

The authoritative contract is `contracts/api-contracts/src/main/resources/openapi/signalharvester-v1.yaml`:

- `POST /api/v1/auth/login` — built-in username/password authentication and browser credential issuance;
- `POST /api/v1/auth/logout` — SignalHarvester-owned browser credential clearing for the JWT and CSRF cookies;
- `GET /api/v1/auth/me` — current stable identity and explicit roles;
- `GET/POST /api/v1/admin/users` — identity list/create;
- `GET/PUT /api/v1/admin/users/{userId}` — identity read and enabled/role replacement.

The module does not expose password hashes, signing secrets, CSRF signing material, or JWT token contents through its REST DTOs.

## Owned data

PostgreSQL schema `security`, created by `db/migration/security/V12__create_security_users.sql`:

- `security.users` — stable identity, username, identity type, enabled state, password hash, timestamps;
- `security.user_roles` — explicit additive role assignments.

There is intentionally no login-session or JWT-session table.

## Dependencies

The module depends on Micronaut HTTP/security, JDBC transaction/Flyway infrastructure, and PostgreSQL runtime support. It has no synchronous dependency on another functional module.

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
- issued JWTs are stateless and remain valid until expiry after disablement/role changes;
- local passwords are stored only as salted PBKDF2-HMAC-SHA256 hashes;
- browser JWTs are not exposed to JavaScript or URL query parameters;
- cookie-authenticated mutations require CSRF validation when security is enabled;
- the first administrator has no repository-known default password.

## Extension points

External identity-provider federation, MFA, password reset, self-service registration, refresh/revocation state, machine-specific BOT permissions, and immediate token revocation require separate requirements. Do not introduce a session table or hidden role hierarchy to implement them implicitly.
