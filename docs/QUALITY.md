---
type: Quality Guide
title: Code quality and coverage
description: SignalHarvester code-quality tooling, coverage reporting, dependency analysis, report locations, and baseline policy.
---
# Code quality and coverage

## Purpose

This document owns repository-wide code-quality and coverage tooling. Test strategy and test execution remain in [`TESTS.md`](TESTS.md).

The quality-tooling rollout is intentionally **baseline first**.

Collect trustworthy reports before introducing numeric or warning-count gates whose starting values are not yet known.

## Canonical workflows

Use the routine correctness gate during normal development:

```bash
./run_checks.sh
```

Run slower baseline/quality checks periodically with:

```bash
./run_rare_checks.sh
```

`run_rare_checks.sh` first runs `run_checks.sh`, then runs:

```bash
./gradlew jacocoAggregateReport --no-watch-fs
./gradlew spotbugsMain --no-watch-fs
./gradlew buildHealth --no-watch-fs
./tools/project-metrics/run_signalharvester_metrics.sh
```

This split keeps the everyday gate focused while preserving a single self-contained command for the broader quality baseline.

## JaCoCo

JaCoCo measures combined unit and integration-test coverage for handwritten production Java code.

The aggregate report excludes:

- `testing/**` support code;
- projects without handwritten `src/main/java` sources;
- common Micronaut-generated classes.

Generated framework bytecode therefore does not distort application coverage.

Reports:

```text
build/reports/jacoco/aggregate/html/index.html
build/reports/jacoco/aggregate/jacoco.xml
```

There is currently **no repository-wide numeric coverage threshold**.

The current goal is to establish a stable baseline and identify important uncovered behavior. Do not optimize coverage percentages by adding low-value tests or testing implementation trivia.

A later PATCH may introduce module-specific or aggregate thresholds after the baseline has been reviewed.

## SpotBugs

SpotBugs analyzes handwritten production Java bytecode. Test-support projects are excluded from the production static-analysis scope.

Reports are generated per analyzed project:

```text
<project>/build/reports/spotbugs/main.html
<project>/build/reports/spotbugs/main.xml
```

The initial rollout uses report mode: findings are visible but do not fail the build. Review the reports before promoting selected confidence/severity levels into hard gates.

Do not suppress a finding merely to make the report clean. Prefer fixing the underlying problem; add a suppression/filter only when the finding is demonstrably not actionable and the reason is documented.

## Dependency analysis

The Gradle dependency-analysis plugin evaluates JVM project dependencies for issues such as:

- unused declared dependencies;
- transitively used dependencies that should be declared directly;
- incorrect `api` versus `implementation` placement;
- other dependency-configuration advice supported by the plugin.

Run directly with:

```bash
./gradlew buildHealth --no-watch-fs
```

Primary report:

```text
build/reports/dependency-analysis/build-health-report.txt
```

Dependency analysis also starts in report/warn mode.

The first runs may expose existing dependency debt. Review and fix that debt coherently instead of immediately converting it into exclusions.

For module-level investigation, use the plugin's `projectHealth` and `reason` tasks as needed.

## Gate policy

Current repository policy:

| Check | Current mode | Intended direction |
|---|---|---|
| Unit/default tests | fail | fail |
| Integration tests | fail | fail |
| ArchUnit boundaries | fail | fail |
| JaCoCo aggregate coverage | report | introduce justified thresholds after baseline review |
| SpotBugs production analysis | report | fail on an agreed actionable confidence/severity baseline |
| Dependency analysis | warn/report | fail on agreed dependency rules after current advice is resolved |
| FULL archive cleanliness | fail | fail |

Quality gates must remain signal-bearing. Do not introduce thresholds or suppressions solely to produce a green build.


## Project size metrics

`tools/project-metrics/count_project_size.py` is a reusable, project-agnostic counter. SignalHarvester-specific inclusion rules live in `tools/project-metrics/run_signalharvester_metrics.sh`.

The SignalHarvester report separates:

- production code;
- tests and test material;
- configuration, contracts, and scripts;
- non-test project totals;
- project-plus-tests totals.

Documentation, generated/build/cache output, archives, binaries, and test trees are excluded from the non-test project metric.

Reports:

```text
build/reports/metrics/project-size.txt
build/reports/metrics/project-size.json
```

The metric counts raw file bytes and physical lines.

It is a repository-size/trend signal, not a quality target. Do not optimize code merely to reduce the number.
