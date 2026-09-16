---
type: Module Overview
title: SignalHarvester application
description: Micronaut composition-root overview for the single deployable backend application.
---
# SignalHarvester application

## Ownership

`app` is the initial single deployable backend and Micronaut composition root.

It composes functional modules and owns startup/global runtime wiring. It must not become the owner of business logic that belongs to a functional module.

## Current state

`io.signalharvester.Application` is the runnable Micronaut entry point. The application is configured as the single Netty-based backend runtime and composes all functional modules. Micronaut's `blocking` executor is explicitly Virtual-Thread backed for the Java 21 baseline.

The assembled application currently wires PostgreSQL/Flyway, Kafka-compatible messaging, source and monitoring-profile configuration, manual and scheduled collection, analysis inspection, Results REST reads, Results SSE live delivery, Event Observation REST/SSE diagnostics, Micronaut health probes, Prometheus metrics, OpenTelemetry instrumentation, and the accepted security module. Blocking controllers owned by the functional modules use `@ExecuteOn(TaskExecutors.BLOCKING)` for JDBC and synchronous workflows. The Results and Event Observation SSE controllers remain reactive; their JDBC polling is offloaded to the blocking executor instead of moving the streaming boundary itself onto a blocking controller executor. Trace export is disabled by default; log correlation and application telemetry do not require an external collector for local startup. The default environment keeps Micronaut Security disabled for the existing trusted local workflow; the `security` environment enables JWT/cookie authentication, CSRF, user persistence, the endpoint role matrix, and restrictive external-source destination policy once required secrets are supplied.

## Read next

- [`../AGENTS.md`](../AGENTS.md)
- [`AGENTS.md`](AGENTS.md)
- [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md)
- [`../docs/IMPLEMENTATION.md`](../docs/IMPLEMENTATION.md)
