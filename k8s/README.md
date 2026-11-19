# Kubernetes manifests for cosec-gateway-server

Kustomize manifests for the gateway image `ghcr.io/ahoo-wang/cosec-gateway`.

| Directory | What it is |
|---|---|
| [`base/`](base) | The gateway's Deployment, Service and `application.yaml`. The overlays build on it. |
| [`overlays/dev/`](overlays/dev) | One small gateway and a single Redis, for trying CoSec out on any cluster, kind included. |
| [`overlays/production/`](overlays/production) | Your own Redis, an autoscaler, a disruption budget, a network policy, 2Gi per gateway. |
| [`examples/`](examples) | Manifests to copy: a `NetworkPolicy` that lets only the gateway reach a service in `gateway` mode. |

The gateway's probes are `/livez` and `/readyz` on its port 8080; the actuator (`/actuator/health`, `/actuator/prometheus`)
is on the management port 8081, a container port the Service does not publish. The CORS origin, the discovery list and
the `cosec-auth` route in `base/application.yaml` are a demo: replace them.

```bash
kubectl apply -k k8s/overlays/dev
```

First create the Secret `cosec-jwt-secret`. Then publish the platform document: until it is installed, every gateway
stays unready. The steps, what each overlay sets and why, and the memory model are in
[Deploying the gateway server](https://cosec.ahoo.me/operations/deployment#kubernetes).
