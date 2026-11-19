# The system test

A deployed CoSec, driven over the network only: the gateway image built from the commit under test
(`platform/gateway-server/Dockerfile`, its AOT cache trained), the services of `test/system-*`, and Redis with
`maxmemory-policy noeviction`, started by [`compose.yaml`](compose.yaml). It proves the wiring between processes, which no
lower test can see; what a unit, slice or embedded-server test already proves is not proved again here.

| Path | What it is |
|---|---|
| `compose.yaml` | The topology. |
| `gateway/application.yaml` | The gateways' configuration, mounted over the image's `config/`. |
| `documents/` | The documents the scenarios publish with `cosec-migrate`. |
| `docker/app.Dockerfile` | The image of each `test/system-*` service, from its `installDist`. |
| `src/test/kotlin/` | The harness and the scenarios (`@Tag("system")`), Kotlin over the JDK's HTTP client. |
| `scripts/summary.py` | The run summary: each failed scenario, the bound it waited for, the evidence artifact. |
| `../system-behind/` | A service behind the gateway in `cosec.pipeline.mode=gateway`: it echoes what reached it. |

## Running it

On CI, through [`.github/workflows/system-test.yml`](../../.github/workflows/system-test.yml): nightly on `main`, on the
pull requests that change it, and on demand for any commit:

```bash
gh workflow run system-test.yml --ref main -f ref=<commit>
```

`-f image=ghcr.io/ahoo-wang/cosec-gateway:<tag>` tests a published image instead of building one. A run uploads the
containers' logs and state, the JUnit results and each scenario's transcript (`build/system/transcripts/`) as the artifact
`system-test-<run id>-<attempt>`, and its summary names each failed scenario and the bound it waited for.

`./gradlew :system-test:check` runs only the harness's own tests, without Docker. The scenarios (`:system-test:systemTest`) need the
stack running on an empty Redis, and the environment the workflow sets (`COSEC_SYSTEM_*`); run them on CI, not on a shared
machine.

## Rules of the suite

- **Over the network only.** Scenarios send HTTP requests to the gateways and services, run `cosec-migrate` (the
  documented operator path) and, for faults, `docker compose`. They never read Redis or a process's internals.
- **No sleeps, no retries.** Every wait is `eventually(bound)`, which polls until the system answers as expected or the
  bound passes. Each bound in `Bound.kt` is a documented property of CoSec, named with its rule. Nothing is retried:
  a scenario that fails once is a finding.
- **The cold start runs first** (`@Order(0)`), on the empty Redis of a fresh stack. Every other scenario uses its own
  tenant and principals, so they run in any order.
