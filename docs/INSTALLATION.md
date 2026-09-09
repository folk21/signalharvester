---
type: Installation Guide
title: Installation
description: Developer-machine prerequisites and setup for the current SignalHarvester backend foundation.
---
# Installation

## Scope

This document owns backend and backend-infrastructure setup. Frontend installation belongs to the separate `signalharvester-ui` repository README.

## Current prerequisites

The current implementation requires:

- JDK 21;
- the repository Gradle Wrapper;
- network access to Maven/Gradle repositories on the first dependency resolution.

Docker is not required to start the current application foundation, but it will be required for Testcontainers and local PostgreSQL/Kafka work as those adapters are implemented.

## Verify Java

```bash
java -version
```

The active build toolchain is Java 21.

## Verify the Gradle Wrapper

```bash
./gradlew --version
```

If archive extraction did not preserve executable permission:

```bash
bash ./gradlew --version
```

Do not depend on a globally installed Gradle.

## UI setup

The companion UI is not installed from this repository. Use the `signalharvester-ui` root README for frontend prerequisites and development-server configuration.
