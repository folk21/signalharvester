---
type: Specification
title: Capacity and observability baseline
description: Bounded measurement stage for reproducible end-to-end Kubernetes pipeline capacity observations before performance optimization or ML-based operational analysis.
document_role: subspec
spec_status: verification-pending
parent: ../spec-signal-harvester-platform.md
---
# Capacity and observability baseline

## Status

Implementation of the first bounded measurement harness is complete and verification is pending.

The previous `backend-analysis-all-relevant-default` slice remains independently verification-pending. This capacity slice does not change or claim acceptance of that behavior.

## Feature scope

- `OBSERVABILITY.APPLICATION`
- `OBSERVABILITY.INFRASTRUCTURE`
- `SCALABILITY.KAFKA_CONSUMERS`
- `TESTING.DETERMINISTIC_LOCAL`
- `COLLECTION.RUNS`
- `ANALYSIS.OUTBOX`
- `RESULTS.MATERIALIZATION`

## Goal

Establish a reproducible local Kubernetes capacity baseline before changing throughput-sensitive production behavior.

The baseline must answer how one bounded workload moves through Collection, Kafka, Analysis, the Analysis outbox, and Results on a known local deployment. It must preserve raw measurements so later stages can decide which capacity risks are real instead of optimizing the deferred backlog speculatively.

## Requirements

### CB1 — deterministic bounded workload

The first capacity scenario must use repository-owned deterministic fixture data and must not depend on the public Internet, external SaaS, or production credentials.

The workload must have explicit bounds for Source count and items per Source. It must exercise the real Collection Run, Kafka, Analysis, outbox, and Results path rather than replacing those boundaries with mocks.

### CB2 — comparable starting state

A measurement must wait for Analysis, Results, and Event Observation consumer-group coordination to settle after backend rollout, then require zero consumer lag and no pending Analysis outbox rows before workload generation. Scheduled Collection must be disabled for the measurement window so unrelated due profiles do not contaminate the workload.

The scenario must fail rather than silently report a contaminated baseline when either condition is not true.

A healthy measurement must also verify that the Analysis DLQ record count did not advance.

### CB3 — machine-readable evidence

Each successful measurement must write a JSON report under `build/reports/performance/` by default.

The report must contain at least:

- scenario/schema identity;
- UTC start time;
- namespace, backend replica count, and backend image;
- workload shape;
- starting lag/outbox/DLQ state;
- periodic Analysis, Results, and Event Observation consumer lag plus durable Analysis row count, durable Results row count, and pending outbox count;
- Collection publication duration;
- final pipeline-drain duration;
- observed item rates derived from that run.

The report is measurement evidence, not a benchmark certificate.

### CB4 — no invented budgets

This stage must not introduce latency SLOs, throughput pass/fail targets, CI performance gates, or capacity claims based on one developer environment.

Performance thresholds may be proposed only after repeated measurements on controlled hardware establish normal variance and a concrete product/operational requirement defines the acceptable boundary.

### CB5 — safe local lifecycle

The harness must restore any backend replica/environment changes and remove temporary Source/Profile configuration when practical.

It must not add a bulk history-deletion contract only for performance testing. Durable Collection/Analysis/Results history may remain, so live measurements should use a disposable local database when clean history matters.

### CB6 — actionable preflight failures

The live capacity workflow must identify which Kubernetes workload failed readiness and expose focused cluster diagnostics instead of returning an anonymous rollout timeout. A caller-provided rollout timeout must also bound the deployment preflight.

### CB7 — preserve production semantics

The first stage must not change scheduler, outbox, Kafka consumer, SSE, database transaction, retry/DLQ, or autoscaling semantics merely to improve measured numbers.

The next optimization slice must be selected from observed bottlenecks.

## Scenarios

### S1 — one-replica pipeline baseline

Generate a bounded deterministic workload against one backend replica and persist the complete drain report.

### S2 — replica comparison

Repeat the same workload shape with another explicit replica count and compare reports manually. The scenario records observations but does not declare an expected performance winner.

Kafka partition count remains a real upper bound on parallel consumer ownership.

## Non-goals

This first slice does not implement:

- ramp, spike, stress, or soak workload modes;
- concurrent Results REST load;
- many-subscriber SSE load;
- scheduler-heavy profile load;
- additional production capacity metrics;
- hard benchmark budgets;
- KEDA or other automatic scaling policy;
- automatic remediation;
- ML or neural-network anomaly detection.

## Validation

Deterministic repository validation:

```bash
./infra/kubernetes/run_tests.sh
./gradlew clean check --no-watch-fs
```

Live measurement acceptance requires an already verified local Kubernetes deployment:

```bash
python3 infra/kubernetes/performance/run_baseline.py
```

Before this slice is accepted, run the canonical backend gate:

```bash
./run_checks.sh
```

Acceptance evidence must include at least one generated JSON report from the live Kubernetes scenario. Numeric results must be reported as observations for that environment, not generalized capacity limits.

## Implementation tasks

- [x] add the bounded deterministic pipeline baseline runner;
- [x] write machine-readable JSON measurement output;
- [x] add deterministic tests for parsing/report semantics and workload bounds;
- [x] document the opt-in Kubernetes workflow and limitations;
- [x] wire the workflow into test/roadmap navigation;
- [x] make live preflight readiness failures identify the workload and emit focused diagnostics;
- [x] tolerate aggregate `rpk` group JSON during transient rebalance while requiring a settled group before baseline measurement;
- [ ] run a live Kubernetes baseline and retain/report its measurements;
- [ ] run `./run_checks.sh` successfully in a Docker-capable environment;
- [ ] accept/archive this spec only after the live measurement and canonical gate pass.

## Kubernetes preflight diagnostics

The shared local Kubernetes verification must fail fast before workload rollout waits when any cluster node is not `Ready`.
