---
type: Infrastructure Guide
title: Operational scenario evaluation evidence
description: Versioned bounded evidence artifacts emitted by Kubernetes resilience and capacity scenarios for offline Health and assisted-investigation evaluation.
---
# Operational scenario evaluation evidence

This directory owns the infrastructure-side artifact format used to turn controlled Kubernetes scenarios into labeled operational-intelligence evidence.

The artifact deliberately separates **ground truth** supplied by the scenario harness from **observed evidence** produced by SignalHarvester. A detector result therefore cannot become its own expected answer. `groundTruth.faultInjected` means only that the harness deliberately introduced the labeled condition; it does not prescribe an expected Health status, alert severity, or human-attention decision.

Each scenario record contains:

- a stable scenario id and label;
- whether the harness intentionally injected a fault and which subsystems it affected;
- a bounded start/end window and terminal harness outcome;
- durable Operations scenario markers;
- explicitly captured Health Snapshots;
- curated scalar measurements observed by the harness;
- bounded selected log/trace evidence when a scenario captures it;
- companion artifact references such as a capacity report, including a digest when available;
- explicit limitations when some evidence cannot be collected safely or meaningfully.

The format is versioned by `schemaVersion`. It is an offline evaluation artifact, not a runtime database contract and not an alert-authority input.

Default live outputs are written below `build/reports/operational-intelligence/` or next to the capacity report that produced them. Generated evidence must stay out of source control. Explicit Health Snapshot captures use the real Operations persistence path and therefore participate in rolling history; current live runners preserve scenario order and report this fact in source metadata. Detector scoring must account for that shared-history limitation until isolated/replay evaluation is added.

Future detector and assisted-investigation evaluators should consume the same scenario artifact rather than maintaining separate expected-data formats.
