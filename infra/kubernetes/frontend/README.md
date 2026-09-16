---
type: Infrastructure Guide
title: SignalHarvester Web Kubernetes boundary
description: Runtime image contract for deploying the separately owned SignalHarvester Web application into the local Kubernetes namespace.
---
# SignalHarvester Web Kubernetes boundary

The frontend source and image build remain owned by the separate `signalharvester-web` repository. This directory defines only the backend repository's local Kubernetes runtime boundary required by platform R24.

The expected local image is `signalharvester-web:local`. It must:

- contain the production frontend build from the frontend repository;
- serve HTTP on container port `8080` as a non-development static application server;
- be built with `VITE_API_BASE_URL=http://localhost:8080` when the documented local port-forward workflow is used;
- remain independently buildable from the backend image.

After loading that image into the local Kubernetes cluster, apply the workload:

```bash
kubectl apply -k infra/kubernetes/frontend
kubectl -n signalharvester rollout status deployment/signalharvester-web --timeout=180s
kubectl -n signalharvester port-forward service/signalharvester-web 5173:8080
```

The root Kubernetes stack does not apply this workload automatically because this repository does not own or build frontend artifacts. Full umbrella `DEPLOYMENT.KUBERNETES` acceptance requires verification with a real image from `signalharvester-web`; backend-only cluster verification is not sufficient for that final platform requirement.
