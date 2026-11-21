# The system test

A deployed CoSec, driven over the network only: two replicas of the gateway image built from the commit under test (and a third that refuses a token while revocations cannot be read), two replicas of the control-plane host image built from it, a single-node Kafka the gateways and the hosts audit to
(`platform/gateway-server/Dockerfile` and `platform/control-server/Dockerfile`, their AOT caches trained), the services of `test/system-*`, and Redis with
`maxmemory-policy noeviction`, started by [`compose.yaml`](compose.yaml). It proves the wiring between processes, which no
lower test can see; what a unit, slice or embedded-server test already proves is not proved again here.

| Path | What it is |
|---|---|
| `compose.yaml` | The topology both deployments share: Redis, Kafka, the gateways, the service behind them, the IAM and the enforcer. |
| `compose.control-host.yaml` | Leg 1, added to it: two replicas of the control-plane host image, and the IAM calling the host's service port. |
| `compose.embedded.yaml` | Leg 2, added to it: the IAM with the control plane embedded (and a second one with its control routes off); the gateways route `/cosec/control` to the IAM. |
| `gateway/application.yaml` | The gateways' configuration, mounted over the image's `config/`: the routes, two of which (`/shop`, `/ledger`) name an app, and `/cosec/control` to `control-1`. |
| `documents/` | The documents the scenarios publish: the platform's with `cosec-migrate`, the apps' and the tenants' through the control plane. |
| `profile/shortened-bounds.yaml` | Test configuration only: the bounds the suite shortens (shadow windows), each with the default it replaces. |
| `../../typescript/cosec-fetcher/test-system/` | The TypeScript client's scenarios against the gateway, run by `pnpm test:system` in `typescript/` after the Kotlin ones. |
| `kind/` | The kind leg: the manifests of `k8s/` on a real cluster (`run.sh`, its platform document). A second job of the same workflow. |
| `docker/app.Dockerfile` | The image of each `test/system-*` service, from its `installDist`. |
| `src/test/kotlin/` | The harness and the scenarios (`@Tag("system")`; the control plane's service path is also `@Tag("service-path")`), Kotlin over the JDK's HTTP client. |
| `scripts/summary.py` | The run summary: each failed scenario, the bound it waited for, the evidence artifact. |
| `../system-behind/` | A service behind the gateway in `cosec.pipeline.mode=gateway`: it echoes what reached it. |
| `../system-auth/` | The IAM (WebFlux, `enforce`, no control plane of its own in this deployment): a test login, the starter's refresh and logout routes, and `POST /admin/onboard`, which calls the control-plane host's service port as the IAM's own principal `svc-iam` with CoSec's public `CoSec-Identity` writer. |
| `../system-enforcer/` | A service that enforces on its own, on Spring MVC and Tomcat, for the app `orders`. |

## Two deployments

The control plane reaches an application in one of two ways, and the suite proves each on a stack of its own (two control
planes on one store would share documents, leases and changes, and every assertion would hold for both or neither).
The workflow runs the suite twice, a matrix of `leg`; `COMPOSE_FILE` names `compose.yaml` and the leg's file, and
`COSEC_SYSTEM_TOPOLOGY` tells the harness which.

| Leg | Stack | Scenarios (Gradle: `-PsystemLeg=`) |
|---|---|---|
| `control-host` (default) | the gateways, the control-plane host (the image built from the commit) and an IAM that calls its service port | every scenario but the `embedded` ones; the TypeScript client |
| `embedded` | the gateways and an IAM that embeds the control plane (`cosec.pipeline.caller=forwarded-identity`, the SDK in process) | the scenarios tagged `control`, which both legs run, and `embedded`, which only this one does |

`control` marks the scenarios of the control plane that do not depend on how it is deployed (the cold start, documents, a policy
change, onboarding a tenant): they speak HTTP to the gateway's `/cosec/control` route, the same URL in both legs.
`service-path` marks what only the control host has (its service port, its three ports, its replicas).

## Running it

On CI, through [`.github/workflows/system-test.yml`](../../.github/workflows/system-test.yml): nightly on `main`, on the
pull requests that change it, and on demand for any commit:

```bash
gh workflow run system-test.yml --ref main -f ref=<commit>
```

`-f image=ghcr.io/ahoo-wang/cosec-gateway:<tag>` tests a published image instead of building one. A run uploads the
containers' logs and state, the JUnit results and each scenario's transcript (`build/system/transcripts/`) as the artifact
`system-test-<run id>-<attempt>`, and its summary names each failed scenario and the bound it waited for.

A build that fails with "configuration cache state could not be cached" may be a failed download underneath (a `429` or a `404` for
an artifact that exists): read the cause below it. The `Resolve the dependencies` step retries the downloads before the build.

`./gradlew :system-test:check` runs only the harness's own tests, without Docker. The scenarios (`:system-test:systemTest`) need the
stack running on an empty Redis, and the environment the workflow sets (`COSEC_SYSTEM_*`); run them on CI, not on a shared
machine.

## What it does not show

- **A role held once, in several apps.** `MultiAppScenario` shows a role id that two apps define granting per app when the
  token is for one app: the role comes from the token, and each app sees its own credential's list. Where memberships come
  from a provider (it is asked for a tenant and a principal only), a role held once applies in every app that defines the
  id. The stack has no provider, so the suite makes no claim about that case.

## Rules of the suite

- **Over the network only.** Scenarios send HTTP requests to the gateways and services, run `cosec-migrate` (the
  documented operator path) and, for faults, `docker compose`. They never read Redis or a process's internals.
- **No sleeps, no retries.** Every wait is `eventually(bound)`, which polls until the system answers as expected or the
  bound passes. Each bound in `Bound.kt` is a documented property of CoSec, named with its rule. Nothing is retried:
  a scenario that fails once is a finding.
- **The cold start runs first** (`@Order(0)`), on the empty Redis of a fresh stack. Every other scenario uses its own
  tenant and principals, so they run in any order.
