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
- evaluation expectations kept outside ground truth: Health `DETECT`, `REMAIN_HEALTHY`, or `DESCRIPTIVE_ONLY`, and alert `ATTENTION_REQUIRED`, `NO_ATTENTION`, or `DESCRIPTIVE_ONLY`;
- a bounded start/end window and terminal harness outcome;
- durable Operations scenario markers, including `FAULT_START`/`FAULT_END` for controlled resilience faults;
- explicitly captured Health Snapshots;
- curated scalar measurements observed by the harness;
- bounded selected log/trace evidence when a scenario captures it;
- companion artifact references such as a capacity report, including a digest when available;
- explicit limitations when some evidence cannot be collected safely or meaningfully.

The evidence format is versioned by `schemaVersion`. `evaluationExpectations` is an additive version-1 field. Older version-1 artifacts that do not contain it remain readable, but the evaluator treats them as `DESCRIPTIVE_ONLY`; historical `faultInjected=true` records are never retroactively interpreted as a requirement to degrade Health or open an alert. It is an offline evaluation artifact, not a runtime database contract and not an alert-authority input.

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

- detection success and time-to-detection only for scenarios explicitly marked `DETECT`;
- `DETECT` scenarios are excluded from detection-rate scoring when a captured pre-fault snapshot is already `DEGRADED`/`UNHEALTHY`, because the controlled fault did not start from a clean detector state;
- false positives only for scenarios explicitly marked `REMAIN_HEALTHY`;
- descriptive observed degradation for `DESCRIPTIVE_ONLY` scenarios without adding it to quality rates;
- affected-subsystem ground-truth coverage only for ground-truth names with an explicit mapping to a Health component (`BACKEND` → `backend-runtime`, `KAFKA` → `eventing`, `ANALYSIS` → `analysis`, `POSTGRESQL` → `postgresql`, `ANALYSIS_OUTBOX` → `analysis-outbox`, `COLLECTION` → `collection`); unmapped names such as `RESULTS` or `EXTERNAL_SOURCE` are reported separately rather than counted as misses;
- post-fault recovery status and explicit unknown/not-evaluable cases instead of invented misses when evidence is absent.

Alert evaluation is an **offline policy projection**, not persisted runtime alert output. It applies explicit consecutive-DEGRADED, consecutive-UNHEALTHY, HEALTHY recovery, and reopen-cooldown parameters to the captured Health Snapshot sequence. Attention rate is scored only for `ATTENTION_REQUIRED`; alert false positives are scored only for `NO_ATTENTION`; `DESCRIPTIVE_ONLY` remains observable but non-scoring. The report records every applied parameter so two policy configurations can be compared over the same evidence later.

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

The evaluator is intentionally descriptive. It does not modify Health state, create alerts, select a production threshold, or declare one policy configuration superior. Evaluation report schema version 2 makes the expectation-aware scoring semantics explicit. Assisted-investigation quality evaluation below consumes the same bounded scenario identity instead of inventing a second source of ground truth.

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

The artifact uses `selectionMode=MANUAL_EVIDENCE_REVIEW`. It reports the evidence needed for an engineering calibration decision but intentionally contains no winner, score, or automatic production-configuration update. Detector/alert rates are meaningful only when the input scenarios carry explicit scoring expectations; legacy and descriptive-only scenarios remain available for inspection but do not influence those rates.

## Dedicated live Health/alert calibration

`run_health_alert_calibration.py` is the detector/alert-specific live evidence producer. Unlike general resilience acceptance, each initial scenario declares an explicit scoring expectation while keeping the deployed Health and alert policies unchanged.

Run it against an already verified local Kubernetes deployment with:

```bash
python3 infra/kubernetes/evaluation/run_health_alert_calibration.py
```

The initial fixed cases are:

- `NORMAL_OPERATION`: clean outbox baseline, expected `REMAIN_HEALTHY` and `NO_ATTENTION`;
- `OUTBOX_BACKLOG_TRANSIENT`: 300 pending Analysis-outbox rows, expected `DETECT` and `NO_ATTENTION`;
- `OUTBOX_BACKLOG_CRITICAL`: 1,200 pending Analysis-outbox rows, expected `DETECT` and `ATTENTION_REQUIRED`.

The backlog cases use the real Collection → Analysis → transactional-outbox path. The runner slows the outbox dispatcher to a ten-minute poll interval instead of disabling the outbox subsystem, because the same subsystem owns the backlog metrics needed by Prometheus and Health evaluation. It waits for the exact PostgreSQL backlog and corresponding Prometheus gauge to converge before recording `FAULT_START` and capturing the fault Health Snapshot. The transient case is rejected if the oldest pending row reaches 120 seconds before capture. Recovery restores one-second outbox polling, waits for every generated item to reach Results, waits for PostgreSQL and Prometheus backlog evidence to return to baseline, then captures the post-recovery snapshot.

The fixed 300/1,200 magnitudes are versioned dataset inputs, not automatically selected production thresholds. A future Health policy must be deployed and rerun against the same cases rather than being counterfactually projected from old telemetry.

## Repeated live calibration campaign

`run_calibration_campaign.py` is a bounded live orchestration wrapper around the accepted resilience evidence producer, the dedicated Health/alert calibration runner, and the optional capacity baseline. It does not add another detector or another source of ground truth.

Default usage runs three sequential repetitions of resilience followed by dedicated Health/alert calibration, then builds one calibration report:

```bash
python3 infra/kubernetes/evaluation/run_calibration_campaign.py --repeats 3
```

Use `--include-capacity` to add one descriptive `NORMAL_OPERATION` capacity baseline after each Health/alert calibration phase. Repeats are bounded to 2–8. The campaign accepts the same comma-separated alert-policy candidate values as `calibration_report.py`; those values affect only the offline alert projection.

By default each invocation creates a unique directory below `build/reports/operational-intelligence/calibration-campaigns/` containing:

- `resilience-run-NN.json` for every accepted resilience repetition;
- `health-alert-run-NN.json` for every dedicated calibration repetition;
- optional `capacity-run-NN.json` plus the companion capacity report;
- `calibration-report.json`;
- `campaign-manifest.json` with campaign configuration, dataset identities, SHA-256 digests, run outcomes, and the final descriptive calibration-report reference.

The harness runs workflows sequentially, fails fast on a failed live command, writes a failed manifest, and preserves valid partial scenario evidence when the underlying runner emitted it. If a resilience child run fails or returns inconsistent scenario evidence, the campaign invokes the resilience runner's bounded `--repair-baseline-only` path and records the result as `baselineRecovery` on that run. A failed recovery is surfaced in the campaign failure rather than hidden as a warning. A failed dedicated Health/alert child preserves any valid partial evidence and fails the campaign without invoking the PostgreSQL resilience-baseline repair path. An explicit `--output-dir` must be empty or absent so evidence is never silently overwritten. The harness never writes production Health/alert/model configuration. It also does not clear persisted Health history between repetitions, so rolling baselines evolve exactly as they do in the live deployment. General resilience fault/recovery scenarios remain `DESCRIPTIVE_ONLY`; the dedicated calibration runner supplies the explicit scored cases. The capacity baseline is also descriptive-only because it deliberately has no performance/Health budget. Different Health policy versions require separately deployed campaigns, and live assisted-investigation trials remain an explicit optional capture step tied to the emitted scenario/snapshot identities.
