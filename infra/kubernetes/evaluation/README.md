---
type: Infrastructure Guide
title: Operational scenario evaluation
description: Versioned bounded evidence artifacts and deterministic offline Health, alert, and assisted-investigation evaluation for Kubernetes scenarios.
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

The evaluator is intentionally descriptive. It does not modify Health state, create alerts, select a production threshold, or declare one policy configuration superior. Assisted-investigation quality evaluation below consumes the same bounded scenario identity instead of inventing a second source of ground truth.

## Assisted-investigation quality evaluation

`assisted_investigation_evaluator.py` evaluates structured investigation trials against the same scenario/snapshot ground truth without calling a model or changing runtime state. It intentionally uses a separate versioned input artifact because scenario ground truth and model-execution observations have different owners.

Run it with one or more scenario-evidence artifacts plus one or more investigation-evidence artifacts:

```bash
python3 infra/kubernetes/evaluation/assisted_investigation_evaluator.py \
  --scenario-evidence build/reports/operational-intelligence/resilience-scenario-evidence.json \
  --investigation-evidence build/reports/operational-intelligence/assisted-investigation-evidence.json
```

The default output is:

```text
build/reports/operational-intelligence/assisted-investigation-evaluation.json
```

The version-1 assisted-investigation evidence artifact has this bounded shape:

```json
{
  "schemaVersion": 1,
  "artifactType": "signalharvester-assisted-investigation-evidence",
  "generatedAt": "...",
  "source": {
    "runner": "deterministic-replay-or-live-harness",
    "provider": "provider-id",
    "model": "model-id"
  },
  "budgets": {
    "maxToolCalls": 8,
    "maxRounds": 4,
    "maxInvestigationDurationMs": 45000
  },
  "trials": [
    {
      "trialId": "...",
      "scenarioRunId": "...",
      "snapshotId": "...",
      "status": "COMPLETED",
      "execution": {
        "toolCallCount": 3,
        "roundCount": 2,
        "durationMs": 1200
      },
      "allowedEvidenceReferences": ["health-snapshot:..."],
      "discoveredEvidenceReferences": ["prometheus:..."],
      "assessment": {"snapshotId": "...", "source": "PROVIDER", "provider": "provider-id", "model": "model-id", "summary": "...", "suspectedSubsystems": ["KAFKA"], "confidence": 0.8, "observations": ["..."], "hypotheses": ["..."], "evidenceReferences": ["health-snapshot:..."], "recommendedChecks": ["..."], "humanAttentionSuggested": true},
      "claimAnnotations": [
        {
          "claim": "...",
          "classification": "SUPPORTED",
          "evidenceReferences": ["health-snapshot:..."]
        }
      ]
    }
  ]
}
```

`status` is one of `COMPLETED`, `FAILED`, or `BUDGET_EXCEEDED`. Completed trials must contain the structured Incident Assessment and explicit execution counters. `maxToolCalls` may be `0`, matching the runtime single-shot provider mode; `maxRounds` and `maxInvestigationDurationMs` must remain positive. The evaluator verifies that every trial references a captured Health Snapshot from the named scenario and that provider/model identity matches the artifact source.

The repository currently defines and validates this trial contract but does not yet export it from the Java assisted-investigation runtime. A following slice must add bounded capture/export of the final structured assessment together with tool-call, round, and duration counters. Until then, deterministic fixtures or an explicit external evaluation harness may produce the artifact; the evaluator itself never invents missing execution metadata.

The report measures:

- exact normalized affected-subsystem coverage and suspected-subsystem precision for fault scenarios;
- unexpected subsystem claims during `NORMAL_OPERATION` scenarios;
- evidence-reference presence and membership in the captured analysis-package/tool allowlist;
- unsupported causal-claim rate only when explicit `SUPPORTED`/`UNSUPPORTED` annotations exist;
- supported-claim citation coverage;
- tool-call count, round count, investigation duration, and compliance with the budgets recorded in the input artifact;
- advisory `humanAttentionSuggested` counts without treating them as alert decisions;
- descriptive per-provider/model summaries over the same dataset, with no winner selection or ranking.

The evaluator deliberately does **not** infer hallucinations by asking another LLM to grade free-form hypotheses. Real-provider claim quality therefore requires explicit human annotation or a deterministic replay fixture with known support labels. Missing claim annotations produce a not-evaluated/null rate rather than an artificial zero-hallucination result. Evidence-reference validity likewise proves only that a citation was allowed; semantic support for a causal claim comes from the explicit annotation.
