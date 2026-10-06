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

The Java assisted-investigation runtime now exports this exact version-1 contract for completed explicit provider runs through `POST /api/v1/admin/operations/health/assessments/evaluation-trials`. The request supplies the `scenarioRunId` and persisted `snapshotId`; Operations verifies that the selected Health Snapshot references an applied `TEST_SCENARIO` marker with that run id before provider I/O starts. The response contains the persisted structured assessment, actual tool-call/round/duration counters, the package allowlist, tool-discovered references, and the application-owned budgets. Runtime export leaves `claimAnnotations` empty because semantic claim annotation remains an offline human/deterministic-replay responsibility.

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

## Repeated-evidence calibration

`calibration_report.py` combines repeated scenario evidence into one bounded descriptive calibration artifact. It does not change application configuration and does not select a preferred candidate.

Example alert-policy sweep over two repeated scenario runs:

```bash
python3 infra/kubernetes/evaluation/calibration_report.py \
  build/reports/operational-intelligence/resilience-scenario-evidence-run-1.json \
  build/reports/operational-intelligence/resilience-scenario-evidence-run-2.json \
  --degraded-min-consecutive 2,3,4 \
  --unhealthy-min-consecutive 1 \
  --healthy-min-consecutive-to-resolve 1,2,3 \
  --reopen-cooldown-seconds 300,900
```

The default output is:

```text
build/reports/operational-intelligence/calibration-report.json
```

The report keeps three comparison domains separate:

- **Observed Health:** repeated datasets are grouped by the persisted Health Snapshot `policyVersion`. Changing Health thresholds or rolling-baseline parameters therefore requires another versioned live run; the calibration tool does not replay raw telemetry into a synthetic Health Engine.
- **Alert policy:** comma-separated candidate values form a bounded Cartesian grid (maximum 64 candidates). Each candidate is projected over the same captured Health Snapshots and remains separated by observed Health policy version.
- **Assisted investigation:** optional `--investigation-evidence` artifacts are grouped by provider, model, and recorded `maxToolCalls`/`maxRounds`/duration budgets. Budget changes require newly captured trials; no counterfactual model execution is invented.

The artifact uses `selectionMode=MANUAL_EVIDENCE_REVIEW`. It reports the evidence needed for an engineering calibration decision but intentionally contains no winner, score, or automatic production-configuration update.

## Repeated live calibration campaign

`run_calibration_campaign.py` is a bounded live orchestration wrapper around the already accepted resilience/capacity evidence producers. It does not add another detector or another source of ground truth.

Default usage runs three sequential resilience repetitions and then builds one calibration report:

```bash
python3 infra/kubernetes/evaluation/run_calibration_campaign.py --repeats 3
```

Use `--include-capacity` to add one `NORMAL_OPERATION` capacity baseline after each resilience repetition. Repeats are bounded to 2–8. The campaign accepts the same comma-separated alert-policy candidate values as `calibration_report.py`; those values affect only the offline alert projection.

By default each invocation creates a unique directory below `build/reports/operational-intelligence/calibration-campaigns/` containing:

- `resilience-run-NN.json` for every accepted resilience repetition;
- optional `capacity-run-NN.json` plus the companion capacity report;
- `calibration-report.json`;
- `campaign-manifest.json` with campaign configuration, dataset identities, SHA-256 digests, run outcomes, and the final descriptive calibration-report reference.

The harness runs workflows sequentially, fails fast on a failed live command, writes a failed manifest, and preserves valid partial scenario evidence when the underlying runner emitted it. If a resilience child run fails or returns inconsistent scenario evidence, the campaign invokes the resilience runner's bounded `--repair-baseline-only` path and records the result as `baselineRecovery` on that run. A failed recovery is surfaced in the campaign failure rather than hidden as a warning. An explicit `--output-dir` must be empty or absent so evidence is never silently overwritten. The harness never writes production Health/alert/model configuration. It also does not clear persisted Health history between repetitions, so rolling baselines evolve exactly as they do in the live deployment. Different Health policy versions require separately deployed campaigns, and live assisted-investigation trials remain an explicit optional capture step tied to the emitted scenario/snapshot identities.
