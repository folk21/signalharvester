---
type: Changelog
title: SignalHarvester changelog
description: Notable project changes organized by release state, with each change line prefixed by its date.
---
# Changelog

## Unreleased

2026-09-08 — Established the initial SignalHarvester modular-monolith backend skeleton and specification hierarchy.

2026-09-08 — Defined Kafka integration events to use versioned Protocol Buffers contracts while REST/SSE remain JSON-facing contracts.

2026-09-08 — Added the initial repository documentation model, development rules, and companion `signalharvester-ui` project boundary.

2026-09-08 — Bootstrapped the runnable Micronaut application foundation and centralized dependency/plugin versions.

2026-09-08 — Added the first source-configuration Java/OpenAPI contracts and versioned Protobuf event envelope/raw-item schemas with initial contract tests.

2026-09-09 — Added the first collection external-source transport boundary with bounded JDK HTTP fetching, deterministic loopback tests, and Virtual Thread batch coordination.

Future notable change lines must begin with an ISO calendar date (`YYYY-MM-DD`). Time-of-day is intentionally omitted.
