---
type: Infrastructure Guide
title: Operational scenario evaluation
description: Versioned bounded evidence artifacts and deterministic offline Health/alert evaluation for Kubernetes resilience and capacity scenarios.
---
# Operational scenario evaluation

This directory owns the infrastructure-side artifact format and offline evaluator used to turn controlled Kubernetes scenarios into measurable operational-intelligence evidence.

## Scenario evidence

The evidence artifact deliberately separates **ground truth** supplied by the scenario harness from **observed evidence** produced by SignalHarvester. A detector result therefore cannot become its own expected answer. `groundTruth.faultInjected` means only that the harness deliberately introduced the labeled condition; it does not prescribe an expected Health status, alert severity, or human-attention decision.

Each scenario record contains:

- a stable scenario id and label;
- whether the harness intentionally injected a fault and which subsystems it affected;
- a bounded start/end window and terminal harness outcome;
- durable Operations scenario markers, including `FAULT_START`/`FAULT_END` for controlled resilience faults;
- explicitly captured Health Snapshots;
- curated scalar measurements observed by the harness;
- bounded selected log/trace evidence when a scenario captures it;
- companion artifact references such as a capacity report, including a digest when available;
- explicit limitations when some evidence cannot be collected safely or meaningfully.

The evidence format is versioned by `schemaVersion`. It is an offline evaluation artifact, not a runtime database contract and not an alert-authority input.

Default live outputs are written below `build/reports/operational-intelligence/` or next to the capacity report that produced them. Generated evidence must stay out of source control. Explicit Health Snapshot captures use the real Operations persistence path and therefore participate in rolling history; current live runners preserve scenario order and report this fact in source metadata.

## Offline Health and alert evaluation

`offline_evaluator.py` consumes one or more version-1 scenario-evidence artifacts and writes a versioned evaluation report without calling the backend or any telemetry/model provider.

Example:

```bash
python3 infra/kubernetes/evaluation/offline_evaluator.py \
  build/reports/operational-intelligence/resilience-scenario-evidence.json
```

The default output is:

```text
build/reports/operational-intelligence/offline-evaluation.json
```

Health evaluation reports:

- fault-scenario detection success for scenarios that contain a Health Snapshot inside the controlled fault window;
- false positives during normal-operation scenarios;
- time from `FAULT_START` to the first `DEGRADED`/`UNHEALTHY` snapshot;
- affected-subsystem ground-truth coverage and observed-subsystem precision;
- post-fault recovery status;
- explicit unknown/not-evaluable cases instead of invented misses when evidence is absent.

Alert evaluation is an **offline policy projection**, not persisted runtime alert output. It applies explicit consecutive-DEGRADED, consecutive-UNHEALTHY, HEALTHY recovery, and reopen-cooldown parameters to the captured Health Snapshot sequence. The report records every applied parameter so two policy configurations can be compared over the same evidence later.

The defaults mirror the current repository `human-attention-v1` defaults:

```text
degradedMinConsecutiveSnapshots=3
unhealthyMinConsecutiveSnapshots=1
healthyMinConsecutiveSnapshotsToResolve=2
reopenCooldownSeconds=900
```

Override the projection explicitly when evaluating another configuration:

```bash
python3 infra/kubernetes/evaluation/offline_evaluator.py \
  --degraded-min-consecutive 2 \
  --healthy-min-consecutive-to-resolve 3 \
  --reopen-cooldown-seconds 600 \
  build/reports/operational-intelligence/resilience-scenario-evidence.json
```

The evaluator is intentionally descriptive. It does not modify Health state, create alerts, select a production threshold, or declare one policy/model configuration superior. Assisted-investigation quality scoring remains a separate follow-up that must consume the same bounded scenario evidence rather than inventing another expected-data format.
