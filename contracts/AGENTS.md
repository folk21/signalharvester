---
type: Development Guide
title: Contract development rules
description: Repository rules for public HTTP/OpenAPI and Kafka/Protobuf contract ownership and compatibility.
---
# Contract development rules

## Ownership

`contracts/` owns wire/application contract sources, not business implementation.

- `api-contracts/` owns REST/OpenAPI contract sources.
- `event-contracts/` owns Kafka Protobuf contract sources.

## Compatibility

Treat published contracts as compatibility boundaries. Coordinate producer/consumer changes, add tests, and avoid leaking internal persistence/domain structures merely for convenience.

Generated code is build output unless a specific tool requires otherwise. Never hand-edit generated Protobuf Java sources.
