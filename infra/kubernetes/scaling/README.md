---
type: Operations Guide
title: Kafka consumer scaling acceptance
description: Live local-Kubernetes workflow for demonstrating partition-bounded multi-replica Kafka worker scaling.
---
# Kafka consumer scaling acceptance

This directory owns the opt-in live acceptance workflow for `SCALABILITY.KAFKA_CONSUMERS`.

The backend remains one modular-monolith Deployment. Scaling that Deployment creates additional compatible Analysis, Results, and Event Observation consumer instances in their existing shared Kafka consumer groups. The repository-owned application topics currently have three partitions, so this workflow demonstrates useful parallelism up to three backend replicas.

Run it only after the normal Kubernetes stack is healthy:

```bash
./infra/kubernetes/verify-local.sh
python3 infra/kubernetes/scaling/run_acceptance.py
```

The default workflow temporarily scales the backend to one replica, creates a bounded 6,000-item backlog through the normal Source and Collection APIs, restores one Analysis consumer, then scales the backend to three replicas while lag is still positive. It requires three active Analysis clients owning all three raw-event partitions, verifies Results and Event Observation also have three active clients, waits for Analysis lag to drain, and checks durable Analysis/Results state, outbox completion, DLQ stability, and authenticated HTTP availability.

The runner restores the original replica count and temporary outbound-source CIDR configuration in cleanup. Its generated RSS workload reuses the opt-in resilience fixture; no public network source is required.

Use `--sources` and `--items-per-source` only when the local machine needs a different bounded workload. If lag drains before the three-replica assignment can be observed, increase the workload rather than weakening the acceptance assertion.

This workflow is not a production benchmark and does not define a throughput SLA. It demonstrates that additional consumer instances share partitions and drain existing backlog while functional semantics remain intact. KEDA/HPA remains a separate deferred capability.
