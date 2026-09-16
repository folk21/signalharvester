---
type: Specification
title: Backend external-source access security
description: Define a production-safe outbound destination policy for configurable HTTP sources, including DNS resolution, redirect validation, and explicit trusted-local overrides.
document_role: subspec
spec_status: completed
parent: ../spec-signal-harvester-platform.md
---
# Backend external-source access security

## Status

Accepted after the developer repository gate passed on 2026-09-16.

## Feature scope

- `SECURITY.EXTERNAL_SOURCE_ACCESS`
- `CONFIGURATION.SOURCES`
- `COLLECTION.ADAPTERS`

## Goal

Prevent configurable HTTP sources from becoming an SSRF path into loopback, link-local, private, metadata, or other deployment-internal networks when source management is exposed beyond a trusted local environment.

The policy must preserve deterministic local testing and explicitly configured internal integrations without treating URL-shape validation as network authorization.

## Current state

The implementation is accepted. `ConfiguredSource` and the REST/OpenAPI boundary continue to own URL syntax only. Collection now owns runtime destination authorization through a named Netty `AddressResolverGroup` used by the Micronaut HTTP client. DNS lookup is offloaded to a Java 21 Virtual Thread, the complete resolved address set is authorized before Netty receives a socket address, and every new redirect destination passes through the same resolver.

The default trusted-local environment keeps loopback/private fixtures available explicitly. The `security` environment switches the policy to `SECURE`; private, carrier-grade NAT/shared, loopback, link-local, IPv6 unique-local, unspecified, and multicast destinations are then blocked unless the operator explicitly allows an applicable CIDR where override is supported. Existing timeout, response-size, redirect-count, connection-pool, and collection-concurrency bounds remain unchanged.

## Requirements

### E1 — runtime destination authorization

Collection must authorize the effective network destination immediately before outbound access. Configuration-time URI validation alone is insufficient because DNS resolution and redirects are runtime properties.

The authorization boundary belongs to Collection because Collection owns external I/O. Configuration continues to own persisted source syntax and must not perform live DNS/network checks during CRUD.

### E2 — unsafe-address protection

A production/shared-deployment policy must reject destinations resolving to address classes that are not explicitly authorized for source collection, including at least:

- unspecified/any-local addresses;
- loopback addresses;
- link-local addresses, including cloud-metadata-style link-local targets;
- private/site-local IPv4 ranges;
- private/unique-local IPv6 ranges;
- multicast addresses.

Literal IP hosts and DNS names must be evaluated under the same policy. If any resolved address for a hostname is unsafe under the active policy, the request must not silently fall back to another address.

A deployment that intentionally collects from an internal destination must use an explicit operator-controlled allow rule rather than weakening the global policy implicitly.

### E3 — redirect revalidation

Every HTTP redirect target must pass the same destination policy before the next request is sent. The existing bounded redirect count must remain in force.

A public initial URL must not be able to redirect the backend to a blocked internal destination.

### E4 — DNS rebinding / connection binding

The address authorized by the policy must correspond to the address used by the actual connection. A hostname-only precheck followed by an unrelated second DNS resolution is not sufficient protection against DNS rebinding or time-of-check/time-of-use changes.

The implementation must either bind the validated resolution into the HTTP connection path or use an HTTP-client resolver integration that applies the policy to the addresses used for the connection.

### E5 — explicit local-test compatibility

Deterministic loopback test servers and the opt-in local live-backend fixture remain supported through an explicit trusted-local/test configuration. Production/shared security configuration must not inherit that relaxation accidentally.

The secure mode and its local relaxation must be visible configuration rather than environment-specific behavior hidden in code.

### E6 — failure semantics

A blocked destination or redirect must fail before the prohibited outbound connection is attempted.

Collection must surface the condition through its existing source-fetch failure boundary so:

- a normal Collection Run records a source-level fetch failure without cancelling unrelated sources;
- Source Test returns a bounded diagnostic fetch failure;
- no new Kafka event family is required solely for the policy rejection.

Diagnostic text must not disclose secrets or unrelated network configuration.

### E7 — secret separation remains independent

Ordinary source settings must not become a generic secret store while destination policy is added. Credentials and future authenticated-source material remain a separate security concern under `SECURITY.EXTERNAL_SOURCE_ACCESS` and umbrella R30.

## Failure semantics

Destination-policy rejection is deterministic for the resolved/redirect target and is not an automatic retry condition inside one collection attempt. Existing scheduling or a later manual run may retry after configuration or DNS state changes.

Policy evaluation failures must not bypass existing timeout, concurrency, response-size, or redirect bounds.

## Scenarios

### S1 — public source

A configured HTTPS hostname resolves only to permitted public addresses. Collection authorizes the destination and performs the normal bounded request.

### S2 — loopback/private source in shared mode

A configured hostname or literal IP resolves to loopback/private space. Shared-deployment policy rejects it before the outbound connection and the source receives a fetch-failure outcome.

### S3 — redirect to internal address

An allowed source returns a redirect whose target resolves to a blocked address. Collection stops at the redirect and does not contact the blocked target.

### S4 — deterministic local fixture

A test or explicitly trusted local workflow enables the documented local relaxation and may access its loopback fixture without changing production/shared defaults.

### S5 — mixed DNS answers

A hostname resolves to both allowed and blocked addresses. The request is rejected unless an explicit operator rule safely authorizes the effective destination set; the client must not select an unchecked answer.

## Non-goals

This slice does not:

- build a general-purpose corporate egress proxy;
- bypass source authentication, robots policy, rate limits, or access restrictions;
- add a browser-managed arbitrary network allowlist without a deployment security model;
- replace existing HTTP timeout/concurrency/resource limits;
- move DNS/network authorization into the Configuration persistence module.

## Design constraints

- Collection remains the runtime owner of external HTTP access.
- The implementation must work on Micronaut's blocking/Virtual-Thread collection boundary and must not block a Netty event loop.
- No new dependency should be introduced if the JDK and current Micronaut HTTP infrastructure can enforce the policy safely; if resolver/connection binding requires a dependency or framework extension, that decision must be explicit.
- Tests must use deterministic resolvers/transports or local fixtures and must not depend on public DNS or public internet access.
- Redirect and DNS handling must be tested as security behavior, not only as URI-string validation.

## Compatibility / migration

The current trusted local profile may retain an explicit compatibility relaxation so existing loopback development fixtures continue to work.

The `security`/shared deployment path must use the restrictive policy before the backend is exposed as a production-style Kubernetes application. Deployment documentation must make any internal-destination allow rule deliberate and visible.

## Validation

Acceptance requires at least:

1. unit tests for literal IPv4/IPv6 address classification, including loopback, link-local, private/unique-local, unspecified, multicast, and permitted public addresses;
2. deterministic DNS tests for hostnames resolving to permitted, blocked, and mixed address sets;
3. transport-level coverage proving blocked destinations are rejected before connection;
4. redirect coverage proving every hop is revalidated and the existing redirect limit remains bounded;
5. a DNS-rebinding/connection-binding test proving the authorized address set is the one used by the connection path;
6. Source Test and Collection Run regression coverage for policy rejection without cross-source cancellation or Kafka publication;
7. trusted-local fixture coverage for the explicit loopback relaxation;
8. canonical `./run_checks.sh` passing in the developer environment.

## Implementation tasks

1. Choose the Micronaut/JDK integration point that can bind destination authorization to actual connection resolution.
2. Add typed outbound-access configuration with secure shared-deployment defaults and an explicit local/test relaxation.
3. Implement address classification and operator allow-rule semantics.
4. Revalidate every redirect target while preserving the existing redirect bound.
5. Map policy rejection through the existing collection fetch-failure boundary.
6. Add deterministic unit/transport/source-test/run tests.
7. Keep configuration and usage documentation synchronized with verification-pending behavior, then finalize shared deployment documentation after acceptance.
