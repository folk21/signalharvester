---
type: Infrastructure Guide
title: Kubernetes capacity baseline
description: Reproducible bounded pipeline measurement for the local SignalHarvester Kubernetes stack.
---
# Kubernetes capacity baseline

This directory owns the first measurement-oriented capacity workflow for the local Kubernetes deployment.

The workflow is intentionally a **baseline measurement**, not a benchmark claim or a production SLO. It creates deterministic RSS workload, measures the real Collection -> Kafka -> Analysis -> outbox -> Results path, and writes a machine-readable report without enforcing arbitrary latency or throughput thresholds.

## What it measures

`run_baseline.py`:

1. verifies the existing local Kubernetes deployment;
2. deploys the repository-owned deterministic fixture;
3. temporarily allowlists the fixture Service CIDR for backend source access;
4. disables scheduled Collection temporarily and sets an explicit backend replica count;
5. waits for Analysis, Results, and Event Observation consumer groups to settle after rollout, then requires zero starting lag plus an empty pending Analysis outbox;
6. creates bounded Sources and one disabled Monitoring Profile;
7. runs one synchronous Collection Run;
8. samples Analysis/Results/Event Observation consumer lag, Analysis durable rows, Results durable rows, and pending outbox rows until the workload drains;
9. verifies that the healthy run did not advance the Analysis DLQ;
10. writes a JSON report and removes temporary Source/Profile configuration.

The workflow does not delete durable Collection/Analysis/Results history because the application contract intentionally has no bulk test-history cleanup operation. Use a disposable local database when clean history matters.

## Run

Build/load/deploy the backend and pass the normal local verification first. The preflight prints each workload while it waits; if a rollout fails, it prints the failed workload, matching pods, workload description, and recent namespace events before stopping. Then run from the repository root:

```bash
python3 infra/kubernetes/performance/run_baseline.py
```

The default workload is bounded to 6 Sources x 200 items with one backend replica. The report is written to:

```text
build/reports/performance/capacity-baseline.json
```

For a controlled one-versus-three replica comparison with the same workload shape, run:

```bash
python3 infra/kubernetes/performance/run_comparison.py
```

The comparison workflow runs the baseline sequentially for one and three backend replicas by default, preserves each raw baseline report under `build/reports/performance/replica-comparison/`, and writes `capacity-comparison.json` with neutral absolute differences and ratios relative to the first run. Use `--replicas 1,2,3` or a different `--output-dir` when a broader local comparison is useful.

The raw-event topic currently has three partitions, so increasing backend replicas beyond available Kafka partitions does not imply additional Analysis parallelism. Run order can affect a single developer-machine observation, so the comparison report does not declare a winner or performance budget. Repeat controlled runs before treating a difference as stable.

## Report semantics

The report records workload shape, backend replica count/image, starting lag/outbox/DLQ state, periodic pipeline samples, Collection publication duration, final drain duration, derived observed rates, and lag/outbox high-water marks from those samples.

Treat rates as **environment-specific observations**. Do not copy one developer machine's values into SLOs, CI pass/fail thresholds, or production capacity claims. First collect repeated measurements on controlled hardware and understand variance.

`run_comparison.py` keeps the same workload arguments across every requested replica count. Its comparison JSON records raw report paths, run order, per-metric values, absolute differences, and ratios to the first run. These fields are descriptive evidence only; lower timing ratios or higher throughput ratios are not acceptance criteria.

The runner accepts both partition-detail and aggregate `rpk group describe --format json` shapes. A transient `PreparingRebalance`/`CompletingRebalance` state is not accepted as the comparable starting point even when `total_lag` is zero; the runner waits for `Stable` before recording the baseline.

## Capacity telemetry follow-up

The measured one-versus-three comparison justified an outbox-focused observability slice before broader stress testing. The backend exposes global outbox pending depth and oldest-pending age plus local batch, Kafka-publication, and database-operation timing metrics. Grafana uses `max` for the database-global backlog gauges because each backend replica samples the same PostgreSQL backlog. Timer/summary metrics aggregate local work across replicas.

These signals are diagnostic context for subsequent ramp/spike/soak work. They do not change the no-budget/no-winner interpretation of this directory's measurement reports.

## Current limits

This first slice does not yet provide:

- ramp, spike, or soak scenarios;
- concurrent Results REST load;
- many-subscriber SSE load;
- scheduler-heavy workloads;
- Prometheus time-series export into the report;
- hard performance budgets;
- automatic optimization or autoscaling decisions;
- ML-based anomaly detection.

Those remain follow-up stages after the outbox capacity telemetry is live-verified and repeated measurements establish which additional paths need instrumentation.
