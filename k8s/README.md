# Kubernetes manifests for cosec-gateway-server and cosec-control-server

Kustomize manifests for the gateway image `ghcr.io/ahoo-wang/cosec-gateway` and the control plane's host,
`ghcr.io/ahoo-wang/cosec-control`.

| Directory | What it is |
|---|---|
| [`base/`](base) | The gateway's Deployment, Service and `application.yaml`. The overlays build on it. |
| [`control/`](control) | The control plane's host: a Deployment, its Services, **one NetworkPolicy per port**, and the Job that checks them on your cluster. The overlays build on it. |
| [`overlays/dev/`](overlays/dev) | One small gateway, the control plane's host and a single Redis, for trying CoSec out on a cluster of your own that nothing else runs on. **Unsafe on a shared cluster**: it states the control host's boundary, which kind's network plugin, among others, does not enforce. |
| [`overlays/production/`](overlays/production) | Your own Redis, an autoscaler, a disruption budget, a network policy, 3200Mi per gateway (a 2 GiB heap, room for every snapshot and revocation default at its bound); the control plane's host with its own Redis account and 3200Mi. |
| [`examples/`](examples) | Manifests to copy: a `NetworkPolicy` that lets only the gateway reach a service in `gateway` mode. |

The gateway's probes are `/livez` and `/readyz` on its port 8080; the actuator (`/actuator/health`, `/actuator/prometheus`)
is on the management port 8081, a container port the Service does not publish. The CORS origin, the discovery list and
the `cosec-auth` route in `base/application.yaml` are a demo: replace them.

```bash
kubectl apply -k k8s/overlays/dev
```

## The control plane's host

[`control/`](control) runs the image the control plane ships as. It believes the `CoSec-Identity` header of whoever can
reach it, and **does not start until it is told** `COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH=true`. That statement
is true only where the cluster enforces the three NetworkPolicies beside the Deployment, one per port, and no file can say
that, so `control/` does not make it: each overlay does, and `control/` ships the check beside the policies, the Job
`cosec-control-boundary-check`. From a pod no policy admits, it connects to every port of every pod (found by the
headless Service `cosec-control-pods`, which publishes no port), and fails, naming the port, when one answers. Apply the
production overlay this way, every time:

```bash
kubectl -n cosec delete job cosec-control-boundary-check --ignore-not-found
kubectl apply -k k8s/overlays/production
kubectl -n cosec wait --for=condition=complete job/cosec-control-boundary-check --timeout=900s
```

The delete comes first because a Job's pod template cannot change, so an apply that moves the image tag would fail on it,
and a Job that has finished does not run again, so without it the wait would answer last time's verdict. If the check
fails, the statement is false: take it out of your overlay (the host stops) until the policies are enforced. The policies
and the check are one kustomization and never apart.

| Port | What | Who is admitted | Service |
|---|---|---|---|
| 8080 `http` | the operators' API: the gateway routes `/cosec/control/**` here, and states who is calling | the gateway's pods (`app.kubernetes.io/name: cosec-gateway`) | `cosec-control`, port 80 |
| 8082 `service` | the service API: the narrow profile for services that call the control plane directly | exactly the services you name in [`networkpolicy-services.yaml`](control/networkpolicy-services.yaml); as shipped it names a placeholder, so it admits no one | `cosec-control-service`, port 80 |
| 8081 `management` | the probes and `/actuator/prometheus`, outside CoSec | the `monitoring` namespace | none |

**What the policies are, and are not.** They are the control, not defence in depth: the host contains mistakes and keeps the
audit, and does not verify its callers. A workload it admits can state any principal, a platform administrator included,
and be both the author and the approver of a change; so a compromise of the gateway, or of any service you name, is a
compromise of the control plane. Name the fewest services, each by its own label in its own namespace. The policies need a
network plugin that enforces NetworkPolicy; without one they do nothing, and many plugins do not: the boundary check is that
test (kind's kindnet enforces them in recent releases, which the system test checks both ways).

The gateway reaches the control plane through a route to `lb://cosec-control` whose metadata `cosec-app-id` is
`cosec-control` (the image's `cosec.authorization.scope.app-id`). The Deployment's strategy is `Recreate` and its replica
count one: stop the control server before an upgrade that changes the stored format, as the upgrade page says. The probes run
on the management port: readiness stays out of the Service until the platform document is installed, and while the control
store is in an older format. Create the Secret `cosec-control-redis-credentials` (the control host's own Redis account, never
the gateways') for the production overlay.

The image tag of `control/kustomization.yaml` moves with the gateway's at a release (`CONTRIBUTING.md`); `8.0.0-beta.5` is
the first release with a control image.

First create the Secret `cosec-jwt-secret`. Then publish the platform document: until it is installed, every gateway
stays unready. The steps, what each overlay sets and why, and the memory model are in
[Deploying the gateway server](https://cosec.ahoo.me/operations/deployment#kubernetes).
