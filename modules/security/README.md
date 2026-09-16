---
type: Module Overview
title: SignalHarvester security module
description: Persisted identities, additive roles, local credential verification, JWT authentication support, and administrative user management.
---
# SignalHarvester security module

The authoritative ownership and integration boundary is [`contract.md`](contract.md).

## Current implementation

The security module owns persisted application identities in PostgreSQL and exposes the blocking authentication provider used by Micronaut Security when the `security` environment is enabled.

The initial identity model supports:

- `HUMAN` accounts, which always receive the baseline `USER` role;
- `BOT` accounts, which always receive the baseline `BOT` role;
- independent additive `VIEWER` and `ADMIN` assignments;
- enabled/disabled account state;
- case-insensitive unique usernames;
- PBKDF2-HMAC-SHA256 password hashes with per-password random salts and a configurable work factor.

There is no login-session table. Successful login produces a short-lived signed JWT through Micronaut Security. Browser authentication uses an HttpOnly JWT cookie so the same credential works for ordinary REST and native `EventSource` requests. A separate readable signed CSRF cookie protects cookie-authenticated state-changing requests.

`DatabaseAuthenticationProvider` performs PostgreSQL credential lookup on Micronaut's blocking executor. Generated JWTs use the persisted UUID as `sub` and explicit role names as the authorization claim. `SignalHarvesterJwtClaimsValidator` rejects non-UUID subjects, missing/empty role sets, and unknown role values in addition to Micronaut's signature, expiry, issuer, and audience validation.

Administrative identity management is exposed at `/api/v1/admin/users`. Responses never include password hashes or JWT material. Authentication outcomes, administrative identity changes, and denied API requests are logged without password, hash, signing-key, or token contents. The first administrator may be bootstrapped from deployment-provided username/password settings when security is enabled and no `ADMIN` exists.

## Activation

The default local backend remains the existing trusted-environment mode while the companion frontend is migrated. Enable protected HTTP boundaries with the Micronaut `security` environment, for example:

```bash
export MICRONAUT_ENVIRONMENTS=security
export SIGNALHARVESTER_JWT_SECRET='replace-with-a-long-random-secret'
export SIGNALHARVESTER_CSRF_SECRET='replace-with-an-independent-long-random-secret'
export SIGNALHARVESTER_BOOTSTRAP_ADMIN_USERNAME='admin'
export SIGNALHARVESTER_BOOTSTRAP_ADMIN_PASSWORD='replace-with-a-strong-password'
./gradlew :app:run
```

Do not use repository-known production secrets. Production-style deployments must set `SIGNALHARVESTER_AUTH_COOKIE_SECURE=true` behind HTTPS.

## Initial authorization matrix

| Boundary | Required access |
|---|---|
| `/api/v1/auth/login` | anonymous |
| `/api/v1/auth/logout` | authenticated + CSRF for cookie requests; returns `200` and expires the JWT and CSRF cookies |
| `/api/v1/auth/me` | authenticated |
| Results REST and Results SSE | `VIEWER` |
| User administration | `ADMIN` |
| Other current `/api/v1/**` application/admin/diagnostic APIs | `ADMIN` |
| Health and Prometheus endpoints | anonymous operational access in this local stage |

Roles are not hierarchical. `ADMIN` does not imply `VIEWER`, and `USER` alone grants neither capability. A `BOT` identity has no business endpoint access merely because it can authenticate; machine-oriented authorization is added only for a concrete future use case.

## Disablement semantics

Disabling an account prevents later username/password authentication. Already issued JWTs remain valid until their short expiry because the backend deliberately has no persistent session/revocation store in this stage. Role changes likewise apply to newly issued JWTs rather than rewriting previously issued credentials.

## Read next

- [`contract.md`](contract.md)
- [`../AGENTS.md`](../AGENTS.md)
- [`../../docs/specs/active/subspecs/backend-authentication-authorization.md`](../../docs/specs/active/subspecs/backend-authentication-authorization.md)
- [`../../docs/CONFIGURATION.md`](../../docs/CONFIGURATION.md)
