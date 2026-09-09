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

It currently defines CRUD operations and schemas for configurable external sources. REST controllers are not implemented yet.

REST contracts remain independent from generated Kafka/Protobuf transport classes.
