---
type: Module Overview
title: SignalHarvester contracts
description: Entry point for external REST/OpenAPI and asynchronous Kafka/Protobuf contract ownership.
---
# SignalHarvester contracts

## Contract families

- [`api-contracts/`](api-contracts/README.md) — browser/test-client REST/JSON contracts described by OpenAPI.
- [`event-contracts/`](event-contracts/README.md) — asynchronous Kafka event schemas defined in Protobuf.

In-process module collaboration uses Java APIs owned by functional modules and does not require serialization through these wire contracts.
