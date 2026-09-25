---
type: Specification
title: Operational health intelligence and change correlation
description: Persisted health reports, change history, statistical anomaly detection, and optional LLM-assisted read-only investigation over the existing observability stack.
document_role: subspec
spec_status: active
parent: ../spec-signal-harvester-platform.md
---
# Operational health intelligence and change correlation

## Status

Active. Stages 1 and 2 are accepted after the developer confirmed the canonical repository gate. Stage 3 manual/provider-neutral LLM analysis is implemented and verification-pending: Operations exports bounded sanitized analysis packages, validates/persists structured Incident Assessments, supports manual import, and provides an explicit optional OpenAI-compatible local/external adapter behind the provider-neutral `IncidentAnalyst` boundary. No automatic triggers or telemetry tools are enabled yet.

The preceding `backend-capacity-telemetry-expansion`, `backend-capacity-observability-baseline`, and `backend-analysis-all-relevant-default` slices remain separate verification-pending carryover until their canonical acceptance is recorded. This specification does not weaken or replace those acceptance gates.

## Implementation progress

Stages 1 and 2 are accepted. The implemented scope now includes:

- Operations-owned `V16` persistence for sanitized change records and Health Snapshots;
- journal integration for supported Source/Monitoring Profile mutations, Security administration, and bootstrap ADMIN creation;
- best-effort markers for successful controlled Analysis/Results/Event Observation DLQ replay;
- typed repository-tooling markers for deployment tuning and deterministic scenarios;
- ADMIN change-history, snapshot capture/latest, Markdown report, and change-correlation endpoints;
- capacity baseline markers plus a persisted Health Snapshot reference in the generated report;
- count-bounded Health Snapshot retention configured by `SIGNALHARVESTER_OPERATIONS_HEALTH_SNAPSHOT_RETENTION_COUNT`.

Stage 2 replaces the placeholder interpretation with policy `deterministic-statistical-v1`: configurable hard thresholds, rolling median/MAD deviation, structured anomaly evidence, cluster-wide allowlisted Prometheus signals with local outbox fallback, and explicit uncertainty when required telemetry or rolling history is unavailable. Periodic sampling is multi-replica coordinated and telemetry I/O stays outside database transactions. Stage 3 adds `health-analysis-v1`, persisted `IncidentAssessment`, manual assessment import, deterministic fake-provider verification, and an explicit OpenAI-compatible HTTP adapter. The next bounded stage is read-only agentic investigation plus trigger/alert policy.

## Feature scope

- `OBSERVABILITY.HEALTH_INTELLIGENCE`
- `OPERATIONS.CHANGE_JOURNAL`
- `OBSERVABILITY.ASSISTED_INVESTIGATION`
- `OBSERVABILITY.APPLICATION`
- `OBSERVABILITY.INFRASTRUCTURE`
- `EVENTING.CORRELATION`
- `SECURITY.AUTHORIZATION`
- `TESTING.DETERMINISTIC_LOCAL`

Feature identifiers are defined in [`../../../FEATURES.md`](../../../FEATURES.md).

## Goal

Turn the existing metrics/logs/traces and capacity evidence into an operational self-analysis pipeline that can answer, with bounded evidence:

- what is healthy or degraded now;
- what changed relative to a recent baseline;
- which signals are anomalous;
- what configuration, deployment, tuning, or operator changes happened before the health change;
- which subsystem is worth investigating next;
- whether human attention is required.

The first useful result must not require an external SaaS or an LLM. SignalHarvester must be able to produce a persisted structured Health Snapshot and a compact human/LLM-readable Health Report using the existing Prometheus/Loki/Tempo/Grafana stack plus application-owned operational state.

LLM analysis is an optional secondary reasoning layer. It may use either an external provider configured with deployment secrets or a local model adapter. It must consume sanitized bounded evidence and must not become the sole source of health state, severity, or alert decisions.

## Current state and gap

The accepted observability baseline already provides Micrometer/Prometheus metrics, OpenTelemetry traces in Tempo, trace-correlated logs in Loki, Kubernetes/Redpanda telemetry, Grafana dashboards, resilience evidence, and deterministic capacity reports. The latest capacity work also exposes Analysis outbox backlog and dispatcher latency signals.

Stages 1 and 2 have closed the original operational-interpretation gap with a durable Health Snapshot, versioned health-scoring/anomaly policy, and cross-capability change journal. The remaining gap is assisted investigation: Stage 3 now provides manual/provider-neutral structured assessment, while bounded read-only telemetry tools, trigger policy, and alert integration remain future work.

Security administration already emits audit-safe logs, but log lines are not a sufficient general change journal for correlating configuration/runtime changes with later health behavior. Event Observation is also not the authoritative store for this purpose because it is a bounded diagnostic materialization of pipeline events.

## Design principles

1. **Deterministic first.** Health state, numeric scores, anomaly candidates, and alert eligibility are computed by SignalHarvester before optional LLM reasoning.
2. **Changes are first-class evidence.** Supported behavior-affecting changes are recorded through standard journaled mutation procedures and correlated with later health snapshots.
3. **Evidence before explanation.** Reports reference bounded metrics, log patterns, traces, baselines, and change records rather than sending unbounded raw telemetry to a model.
4. **Provider-neutral and local-friendly.** No external observability SaaS is required. LLM providers are adapters and may be disabled or replaced by a local model.
5. **Read-only agent tools.** Automated investigation may query telemetry and history but must not restart pods, scale workloads, replay DLQs, mutate configuration, or execute arbitrary SQL/shell commands.
6. **Untrusted telemetry.** Log/event/source content is evidence, never instructions. Prompt-like text inside telemetry must not alter agent policy.
7. **Correlation is not causation.** A recent change may be reported as temporally correlated with a health shift, but the system must not assert causality without supporting evidence.

## Requirements

### OI1 — persisted Health Snapshot

Feature: `OBSERVABILITY.HEALTH_INTELLIGENCE`.

The backend must define a stable internal Health Snapshot model and persist snapshots so operational state can be compared over time. A snapshot must contain at least:

- generation time and analyzed time window;
- overall health status and a bounded numeric health score;
- health-policy/scoring version;
- subsystem/component statuses;
- normalized signal values and anomaly candidates used by the decision;
- references to relevant recent change records;
- application build/deployment identity when available;
- evidence completeness/unknown-state information when a required source is unavailable.

The initial status vocabulary should remain small, for example `HEALTHY`, `DEGRADED`, `UNHEALTHY`, and `UNKNOWN`. The numeric score is an operational comparison aid, not an externally promised SLO.

Snapshot persistence and retention must be bounded. Health collection failure must not affect the business processing pipeline.

### OI2 — bounded Health Report

Feature: `OBSERVABILITY.HEALTH_INTELLIGENCE`.

SignalHarvester must generate two representations from the same snapshot/evidence model:

- canonical structured JSON suitable for machines and later LLM analysis;
- derived Markdown/text suitable for a human operator or manual upload to a powerful LLM.

The report should cover current health, change from previous/baseline state, anomalous signals, subsystem status, recent operational changes, representative log patterns/traces, capacity/backlog evidence, uncertainties, and evidence references.

The report must be bounded by explicit limits. It must summarize logs/traces rather than embedding uncontrolled volumes of raw telemetry.

### OI3 — durable operational change journal

Feature: `OPERATIONS.CHANGE_JOURNAL`.

Supported changes that can materially affect application behavior or observed health must pass through a standard journaled procedure. The initial journal scope must include at least:

- Source and Monitoring Profile mutations, including enablement, source membership, schedules, extraction settings, and Analysis settings;
- security administration changes that alter enabled state or roles;
- repository-owned operational tuning/deployment changes when they are performed through SignalHarvester tooling, including replica-count/capacity-test changes and runtime configuration markers;
- operator recovery actions that can materially change load or processing state, such as controlled DLQ replay;
- deterministic resilience/capacity scenario markers used to evaluate health analysis.

A durable change record must include, where applicable:

- stable change ID and timestamp;
- actor/principal or system identity;
- change source such as REST/UI, CLI/tooling, deployment workflow, or test scenario;
- change category, target type, and stable target identity;
- sanitized before/after values or a sanitized diff;
- outcome (`APPLIED`, `REJECTED`, or equivalent);
- request/correlation/trace identity when available;
- application/deployment version when available.

Passwords, tokens, secret values, credential headers, and other sensitive material must never be persisted in the journal. Secret-setting changes may record only safe metadata such as key name/scope and that a value changed.

Direct database mutation is not a supported configuration workflow and is outside the journal guarantee. Changes made directly with generic `kubectl` or external tools cannot be guaranteed to appear automatically; repository-owned operational tooling must emit explicit change markers for behavior-affecting changes it performs, and detectable untracked drift may be reported separately. No Terraform-style control plane is required by this feature.

### OI4 — health/change timeline correlation

Features: `OBSERVABILITY.HEALTH_INTELLIGENCE`, `OPERATIONS.CHANGE_JOURNAL`.

Health analysis must be able to retrieve changes that occurred before or during a snapshot window and expose them on one operational timeline.

The engine must support before/after comparison around a selected change and may raise candidate correlations such as “outbox backlog increased after replica-count change.” Candidate correlations must include evidence and must not be presented as proven root cause solely because of temporal proximity.

### OI5 — deterministic and statistical anomaly detection

Feature: `OBSERVABILITY.HEALTH_INTELLIGENCE`.

The first automated detector must use deterministic rules and lightweight statistical methods before any custom neural network is introduced. The detector boundary must be replaceable and may combine:

- hard safety/availability rules;
- rolling baselines;
- rate/ratio changes;
- EWMA or equivalent smoothing;
- z-score/robust deviation;
- bounded change-point or multivariate anomaly techniques when measurements justify them.

Detector output must be structured and include signal identity, observed value/window, baseline/reference, anomaly score or severity, and evidence. Thresholds and scoring policy must be versioned/configurable rather than hidden constants.

A custom neural network remains a later experiment only after sufficient labeled operational history exists and simpler detectors have measurable shortcomings.

### OI6 — manual LLM analysis package

Feature: `OBSERVABILITY.ASSISTED_INVESTIGATION`.

The system must support exporting a sanitized bounded Health Report package that can be manually supplied to an external or local LLM without giving that model direct system access.

This mode is required before automated agentic invocation because it provides a low-risk way to evaluate whether LLM reasoning adds useful operational insight over the deterministic report.

### OI7 — optional provider-neutral LLM analyst

Feature: `OBSERVABILITY.ASSISTED_INVESTIGATION`.

Automated LLM use must sit behind an application-owned provider abstraction. The core health/change model must not depend on a specific vendor, SDK, or model.

The runtime policy must support at least:

- `OFF`;
- explicit/manual invocation;
- event-driven invocation for qualifying anomaly/health transitions;
- optional low-frequency periodic reassessment of an active incident.

External provider credentials must use deployment secrets. A local model adapter must be possible without changing the health engine. Invocation must have explicit timeouts, rate/cost bounds, cooldown/deduplication, and a maximum investigation duration.

No new LLM/agent framework dependency is mandated by this specification. Dependency selection must follow repository approval rules and prefer the smallest OSS/provider-neutral solution that preserves testability.

### OI8 — bounded read-only investigation tools

Feature: `OBSERVABILITY.ASSISTED_INVESTIGATION`.

An agentic investigation may call only explicit read-only tools. The initial tool surface should cover:

- current/previous Health Snapshot and baseline reports;
- bounded Prometheus metric/range queries through allowlisted query IDs or a constrained query layer;
- bounded Loki log-pattern/search queries;
- Tempo trace search and trace retrieval;
- recent operational change history;
- current capacity-baseline evidence.

The initial agent must not receive arbitrary SQL, shell, Kubernetes mutation, unrestricted HTTP, or generic filesystem tools. Tool calls must have result-size limits and a maximum round/tool-call count.

### OI9 — structured assessment and alert decision boundary

Feature: `OBSERVABILITY.ASSISTED_INVESTIGATION`.

LLM output used by the application must conform to a validated structured schema. The assessment should include:

- concise summary;
- suspected affected subsystem(s);
- confidence;
- evidence references;
- hypotheses clearly separated from observations;
- recommended next checks;
- optional suggestion that human attention is warranted.

The LLM must not be the sole authority for `HEALTHY`/`UNHEALTHY` state or alert emission. Alerting must remain an application policy over deterministic/statistical health state, persistence/duration, deduplication/cooldown, and optionally the structured LLM assessment. Free-form model text must never directly trigger an operational action.

### OI10 — telemetry trust, redaction, and prompt-injection boundary

Feature: `OBSERVABILITY.ASSISTED_INVESTIGATION`.

Logs, event payload summaries, external-source text, and trace attributes must be treated as untrusted evidence. Before external LLM transmission, the system must apply a bounded sanitization/redaction step that removes known secrets, authorization material, sensitive headers, and fields explicitly classified as non-exportable.

Agent policy must state that telemetry content cannot issue instructions or change tool permissions. Tool selection and authorization come only from application configuration.

### OI11 — evaluation from controlled operational scenarios

Features: `OBSERVABILITY.HEALTH_INTELLIGENCE`, `OBSERVABILITY.ASSISTED_INVESTIGATION`, `TESTING.DETERMINISTIC_LOCAL`.

Existing and future resilience/capacity scenarios must double as an evaluation dataset source. Representative labels should include normal operation, PostgreSQL outage/latency, Kafka lag or broker restart, pod restart, slow external source, outbox backlog, and load spikes.

Evaluation evidence should preserve the scenario label plus bounded metrics/logs/traces/change markers/Health Snapshots needed for replay or offline assessment.

Detector/LLM evaluation should measure at least:

- incident detection success and false-positive rate;
- time to detection;
- correct affected-subsystem identification;
- unsupported/hallucinated causal claims;
- evidence citation quality;
- agent tool-call count and investigation duration.

Custom ML/neural models must be compared against the simpler baseline using the same dataset before they are considered for runtime use.

### OI12 — operational isolation

Features: `OBSERVABILITY.HEALTH_INTELLIGENCE`, `OBSERVABILITY.ASSISTED_INVESTIGATION`.

Health sampling, report generation, anomaly detection, and LLM investigation must remain auxiliary workloads. They must have explicit scheduling/concurrency/time bounds and must not hold business transactions, consumer threads, or outbox leases while querying telemetry or waiting for a model.

Failure of health analysis or an LLM provider must degrade the intelligence feature to `UNKNOWN`/unavailable evidence as appropriate and must not stop Collection, Analysis, Results, or existing operational observability.

## Initial implementation stages

1. **Change journal + Health Snapshot/Report foundation.** Add durable sanitized change records, persisted Health Snapshots, bounded JSON/Markdown reports, timeline queries, and manual report export. No LLM runtime dependency is required.
2. **Deterministic/statistical Health Engine.** Add versioned rules/baselines/anomaly scoring and change-before/after correlation. Use controlled resilience/capacity scenarios as evaluation evidence.
3. **Manual/provider abstraction.** Add a provider-neutral structured `IncidentAssessment` boundary and an explicitly invoked external/local LLM adapter.
4. **Read-only agentic investigation.** Add bounded Prometheus/Loki/Tempo/change-history tools, investigation budgets, sanitization, and event/periodic trigger policy.
5. **Alert integration and model evaluation.** Combine deterministic health persistence with optional structured LLM assessment for human-attention alerts; only then evaluate whether custom ML/neural models add measurable value.

These stages are ordered to deliver operational value early while keeping LLM and neural-network dependencies optional.

## Non-goals

This specification does not require:

- Datadog, Elastic Cloud, or another paid/hosted observability platform;
- replacing Prometheus, Loki, Tempo, Grafana, or OpenTelemetry;
- Terraform or a general infrastructure/configuration-as-code control plane;
- an autonomous remediation agent;
- arbitrary model-generated PromQL/LogQL/SQL/shell access in the first agentic slice;
- automatic Kubernetes restarts/scaling or DLQ replay initiated by an LLM;
- a custom neural network before evaluation data demonstrates a concrete need;
- sending raw unlimited logs/traces to an external model;
- treating temporal change/health correlation as proven causality.

## Validation

Each implementation stage must add focused deterministic tests before broader live acceptance. The complete feature must ultimately demonstrate:

- durable redacted change records for representative configuration/admin/tooling changes;
- persisted Health Snapshots and bounded JSON/Markdown reports;
- change-before/after correlation without secret leakage;
- deterministic/statistical detection against labeled resilience/capacity scenarios;
- manual report export with no external provider requirement;
- structured LLM assessment using a deterministic fake provider;
- read-only tool-budget enforcement and prompt-injection/redaction tests;
- optional live provider/local-model acceptance kept separate from the canonical offline repository gate;
- canonical `./run_checks.sh` before acceptance of repository-owned behavior.

## Implementation tasks

- [x] define ownership/persistence boundaries for Health Snapshot and operational change journal;
- [x] add `OPERATIONS.CHANGE_JOURNAL` capture for initial configuration/security/tooling mutations;
- [x] add persisted versioned Health Snapshot and bounded Health Report generation;
- [x] expose timeline/before-after correlation between changes and health;
- [x] add lightweight deterministic/statistical detector boundary and baseline implementation;
- [ ] reuse resilience/capacity scenarios as labeled operational-analysis evidence;
- [x] add manual sanitized report export;
- [x] add provider-neutral structured `IncidentAssessment` and deterministic fake provider;
- [x] add optional local/external LLM adapter behind explicit secrets/policy;
- [ ] add bounded read-only Prometheus/Loki/Tempo/change-history investigation tools;
- [ ] add alert decision policy that does not delegate authority to free-form LLM text;
- [ ] run focused and canonical validation, then move accepted behavior into owning current-state documentation.
