---
type: Module Overview
title: SignalHarvester API contracts
description: Entry point for REST/JSON contract sources and OpenAPI ownership.
---
# SignalHarvester API contracts

## Ownership

`api-contracts` owns source definitions for backend REST/JSON APIs consumed by the companion UI and test clients.

OpenAPI sources belong under `src/main/resources/openapi/`.

## Current state

The initial OpenAPI 3.1 contract is [`src/main/resources/openapi/signalharvester-v1.yaml`](src/main/resources/openapi/signalharvester-v1.yaml).

It currently defines CRUD operations and schemas for configurable external sources. Source names must contain at least one non-whitespace character, and source locations require an absolute HTTP(S) URL with a host and without embedded credentials or fragments so the external contract matches the configuration-module invariant. REST controllers are not implemented yet.

REST contracts remain independent from generated Kafka/Protobuf transport classes.
