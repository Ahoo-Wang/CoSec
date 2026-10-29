# Kubernetes manifests for cosec-gateway-server

Kustomize manifests for the gateway image `ghcr.io/ahoo-wang/cosec-gateway`.

| Directory | What it is |
|---|---|
| [`base/`](base) | The gateway's Deployment, Service and `application.yaml`. The overlays build on it. |
| [`overlays/dev/`](overlays/dev) | One small gateway and a single Redis, for trying CoSec out on any cluster, kind included. |
| [`overlays/production/`](overlays/production) | Your own Redis, an autoscaler, a disruption budget, a network policy, 2Gi per gateway. |

```bash
kubectl apply -k k8s/overlays/dev
```

First create the Secret `cosec-jwt-secret`. Then publish the platform document: until it is installed, every gateway
stays unready. The steps, what each overlay sets and why, and the memory model are in
[Deploying the gateway server](https://cosec.ahoo.me/operations/deployment#kubernetes).
