---
type: Infrastructure Guide
title: SignalHarvester observability assets
description: Location and ownership of the Prometheus, Loki, Tempo, Alloy, kube-state-metrics, and Grafana configuration used by the local Kubernetes stack.
---
# SignalHarvester observability assets

The active local observability configuration is currently owned under [`../kubernetes/observability/`](../kubernetes/observability/) because its service discovery, storage, and workload lifecycle are Kubernetes-specific.

The stack uses:

- Prometheus for application, Redpanda, Kubernetes-state, and Tempo-generated metrics;
- Loki for Kubernetes pod logs;
- Tempo for OTLP traces and span-metric generation;
- Grafana Alloy for namespace pod-log collection;
- kube-state-metrics for replica/restart/workload state;
- Grafana for provisioned Prometheus/Loki/Tempo exploration and dashboards.

This directory remains the stable infrastructure-observability entry point if non-Kubernetes deployment variants are added later.
