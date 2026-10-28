---
title: "Deploying the Gateway Server"
description: "Run the cosec-gateway-server image, and use its JDK 25 AOT cache to start faster."
---

# Deploying the Gateway Server

For operators who run `cosec-gateway-server`, the ready-made Spring Cloud Gateway with CoSec in front, from its
Docker image or on Kubernetes, or who build a similar image of their own. This page covers what the image contains,
what it needs before it can answer a request, the Kubernetes manifests in the repository, how CI checks the image, and
the JDK 25 AOT cache it ships with. Every property named here is in [Configuration](./configuration.md).

## The image

CI builds the image from [`cosec-gateway-server/Dockerfile`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-gateway-server/Dockerfile)
on every push and publishes it as `ahoowang/cosec-gateway`, `ghcr.io/ahoo-wang/cosec-gateway` and
`registry.cn-shanghai.aliyuncs.com/ahoo/cosec-gateway`, for `linux/amd64` and `linux/arm64`. The gateway server is an
application: it is published only as an image, never as a library.

- **JDK.** The image runs on Alibaba Dragonwell 25 (`dragonwell:25-ubuntu`). The code is compiled for Java 17 like
  every other CoSec module, so the JDK 17 baseline is unchanged.
- **Required settings.** `COSEC_JWT_SECRET` (there is no default, see
  [Authentication](../integration/authentication.md)) and the Redis address, for example `SPRING_DATA_REDIS_URL`.
- **Layout.** The application lives in `/opt/cosec-gateway-server` and runs as the unprivileged `appuser`. The
  configuration is `config/application.yaml`; the JVM options are in `bin/cosec-gateway-server` (ZGC, a 2 GiB heap).
  Add options through `JAVA_OPTS`.
- **What is in it.** The gateway filter, the Redis snapshot store (`cache-support`), tracing and the Kafka module. Its
  configuration turns Kafka audit (`cosec.audit.kafka.enabled: false`), login (`cosec.authentication.enabled: false`)
  and ip2region (`cosec.ip2region.enabled: false`) off. It has no control plane: documents are published by
  `cosec-migrate` or by a control-plane application on the same Redis.

```bash
docker run -d -p 8080:8080 \
  -e SPRING_DATA_REDIS_URL=redis://redis:6379 \
  -e COSEC_JWT_SECRET=... \
  ahoowang/cosec-gateway
```

## What the gateway authorizes

CoSec runs as a Spring Cloud Gateway global filter, which runs on the gateway's routes only. So every routed request
is authorized, in the `http` namespace (the gateway loads no route map), and the gateway's own actuator endpoints,
health probes included, are not. Routes are ordinary Spring Cloud Gateway configuration under
`spring.cloud.gateway.server.webflux.routes`. How a request becomes an action and a resource, and how outcomes become
responses, is in [Web integration](../integration/web.md).

## Before the first request

The gateway starts without any document, but answers every routed request `503` until a platform document is
published ([The first documents](./storage.md#the-first-documents)). For each environment:

1. **Redis with `maxmemory-policy noeviction`.** Each instance checks it at startup with `CONFIG GET` and does not
   start otherwise ([Redis requirements](./storage.md#redis-requirements)). On a managed Redis that hides
   `CONFIG GET`, set `noeviction` yourself and `COSEC_AUTHORIZATION_SNAPSHOT_EVICTION_CHECK=warn`.
2. **The documents.** Publish them with `cosec-migrate publish --to-redis <uri> --in <dir>`, then name the first
   administrator with `cosec-migrate bootstrap` if a control plane will manage them. The gateway, the control plane and
   `cosec-migrate` must use the same Redis and the same `cosec.authorization.snapshot.key-prefix`.
3. **Instance ids, if you lock Redis down per instance.** The gateway takes part in shadow windows by default, with a
   random instance id. To give each pod its own Redis user, set `COSEC_AUTHORIZATION_SHADOW_INSTANCE_ID` per pod
   ([Who may write which keys](./storage.md#who-may-write-which-keys)).

An instance reports itself healthy and ready before it has loaded the platform document; the first requests wait for
it, up to [`cold-load-timeout`](./configuration.md#snapshot) (2 seconds), and answer `503` if it cannot be read.

## Kubernetes

[`k8s/`](https://github.com/Ahoo-Wang/CoSec/tree/main/k8s) holds example manifests:

| File | Contents |
|---|---|
| `cosec-gateway-config.yaml` | A ConfigMap with `application.yaml` and `bootstrap.yaml`, mounted over `/opt/cosec-gateway-server/config/`: the routes and the `cosec.*` settings above. |
| `cosec-gateway-deployment.yml` | The Deployment: `COSEC_JWT_SECRET` from the Secret `cosec-jwt-secret`, a Redis Cluster from the Secret `redis-secret` (`SPRING_DATA_REDIS_CLUSTER_NODES`, `SPRING_DATA_REDIS_PASSWORD`, adaptive topology refresh), probes on `/actuator/health`, `/actuator/health/readiness` and `/actuator/health/liveness`, and the OpenTelemetry Java agent injection annotation. |
| `cosec-gateway-reids.yaml` | The Secret `redis-secret`, with placeholder values. |
| `cosec-gateway-service.yaml` | A Service, port 80 to the container's 8080. |
| `cosec-gateway-hpa.yaml` | A HorizontalPodAutoscaler, 2 to 10 replicas on CPU. |

The Deployment names the image `ghcr.io/ahoo-wang/cosec-gateway:7.0.0`: set the tag to the version you deploy. Create
`cosec-jwt-secret` yourself (`kubectl create secret generic cosec-jwt-secret --from-literal=secret=<random value>`),
and fill `redis-secret` with your nodes and password. `cosec-migrate` connects to one Redis server through
`--to-redis`.

## How CI checks the image

After the image is pushed, the `aot-smoke` job of
[`docker-deploy.yml`](https://github.com/Ahoo-Wang/CoSec/blob/main/.github/workflows/docker-deploy.yml) runs
[`aot-smoke.sh`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-gateway-server/aot-smoke.sh) against a Redis 7
service container. It publishes a platform document with `cosec-migrate publish`, starts the image with
`-XX:AOTMode=on` (a cache that cannot be mapped fails the job), adds a `/smoke/**` route to the gateway's own health
endpoint, and checks that the health probe answers `200`, an anonymous request `401` with
`application/problem+json`, a request with a valid HS256 token `200`, and a request a DENY matches `403` with
`application/problem+json`. The same steps are a quick check of your own deployment.

## AOT cache

The image ships an AOT cache (JDK 25, [JEP 483](https://openjdk.org/jeps/483), [JEP 514](https://openjdk.org/jeps/514),
[JEP 515](https://openjdk.org/jeps/515)): the classes the gateway loads during startup, already read, parsed,
loaded and linked, plus method profiles from a training run. The JVM maps the file at startup instead of doing that
work again. No code changes and no Spring AOT are involved.

### What it changes

- **Startup, and the first seconds after it.** Time to `Started GatewayServerKt` drops by about a quarter; see
  [Measured startup](#measured-startup). The method profiles let the JIT compile hot methods sooner, which shortens
  warm-up.
- **Not peak throughput.** Once the JIT has compiled the hot paths, a JVM with the cache runs the same code as one
  without it. Authorization performance is in [Architecture overview](../architecture/overview.md#performance).
- **Memory.** The resident set size after startup is larger, because it counts the mapped part of the 104 MiB cache:
  26 MiB in the container and 66 MiB natively in [the measurements](#measured-startup). Those pages are file-backed, not heap.
- **Image size.** The cache adds about 104 MiB to the image.

### How the image uses it

The `Dockerfile` trains the cache in its final stage with
[`aot-train.sh`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-gateway-server/aot-train.sh), against the exact
jars and path the container runs, and writes it to `/opt/cosec-gateway-server/aot/cosec-gateway-server.aot`.

1. **Record.** The script starts the gateway with `-XX:AOTMode=record` and the same JVM options as production,
   waits until `/actuator/health/liveness` answers, sends a few requests, and stops it with `SIGTERM`. It uses
   ephemeral HTTP and JMX ports, because a multi-platform build trains all platforms at once and their JVMs can
   share one network.
2. **Create.** A second run with `-XX:AOTMode=create` turns the recorded configuration into the cache.
3. **Check.** A third run with `-XX:AOTMode=on` fails the build if the production options cannot map the cache.

Training needs no Redis and no network. It sets `cocache.backend=memory`, so CoCache keeps its caches in process
and the whole application starts without a Redis server; the eviction check passes for the in-memory backend. The
trade-off: classes that load only once Redis answers (the Lettuce connection path) or only for a decision on real
documents are not in the cache (with no platform document, every training request is refused). They load the normal
way, so the first authorized request costs what it would cost without a cache.

The container passes `-XX:AOTCache=…` through `JDK_JAVA_OPTIONS`, not `JAVA_OPTS`, so setting `JAVA_OPTS` keeps the
cache. Each start prints `NOTE: Picked up JDK_JAVA_OPTIONS: …` on stderr. Every other `java` command in the
container picks the option up too: one without the gateway's options, such as `java -version`, cannot use the
cache and prints the `[aot]` errors described [below](#when-the-cache-is-not-used), which are harmless.

The cache is not trained with `-Dspring.context.exit=onRefresh`, the mode Spring Boot suggests. The gateway enables
the Spring Cloud bootstrap context, and that context refreshes first, so the JVM exits before the application
context exists. The run returns 0 and prints nothing, and the resulting cache covers almost none of the
application.

### When the cache is not used

A cache that does not match the JVM is ignored: the JVM starts without it, at normal speed, and logs why. This
happens when the cache file is missing or truncated, when a jar on the class path differs from training (path,
size or modification time), when another JDK build runs it, or when the GC differs (for example `-XX:+UseG1GC`
instead of the trained ZGC). The JVM then logs lines such as:

```text
[error][aot] An error has occurred while processing the AOT cache. Run with -Xlog:aot for details.
[error][aot] Unable to map shared spaces
```

That is the default `-XX:AOTMode=auto`. **Never set `-XX:AOTMode=on` in production**: with it, a mismatched cache
stops the JVM from starting. To see whether the cache was used, add `-Xlog:aot` to `JAVA_OPTS`. To turn it off,
start the container with `-e JDK_JAVA_OPTIONS=`.

Changing `config/application.yaml` or the environment does not invalidate the cache, since configuration is not on
the class path. A configuration that turns on more features loads classes the training run never saw; retrain to
cover them.

### Training your own cache

Retrain whenever the jars, the JDK or the GC change. For an image built on top of this one, run the same script
after your changes; it overwrites the cache in place:

```dockerfile
FROM ahoowang/cosec-gateway
COPY --chown=appuser:appgroup application.yaml config/application.yaml
RUN aot-train.sh /opt/cosec-gateway-server /opt/cosec-gateway-server/aot/cosec-gateway-server.aot
```

For your own Spring Boot application on JDK 25, the same two steps apply. Run both with the class path and the JVM
options production uses, from the same directory:

```bash
# 1. Record: run a representative workload, then stop the JVM normally (SIGTERM is fine).
java -XX:AOTMode=record -XX:AOTConfiguration=app.aotconf -cp "$CLASSPATH" com.example.App
# 2. Create: needs no workload and exits by itself.
java -XX:AOTMode=create -XX:AOTConfiguration=app.aotconf -XX:AOTCache=app.aot -cp "$CLASSPATH" com.example.App
# Production
java -XX:AOTCache=app.aot -cp "$CLASSPATH" com.example.App
```

- **Train where you run.** The cache stores the class path, including each jar's modification time. Train in the
  final image stage, after the jars are in place; copying jars to another stage or directory afterwards makes the
  cache unusable.
- **Two steps or one.** JDK 25 also has a one-step mode, `-XX:AOTCacheOutput=app.aot`, which creates the cache
  in a child JVM when the training JVM exits. The image uses two steps because its training run ends with
  `SIGTERM`: each step then has its own exit status, and a failed create cannot hide behind the 143 of a stopped
  process.
- **Keep secrets out of training.** Train with throwaway credentials; build steps and their logs are not the
  place for real ones.
- **Multi-platform builds** train once per platform, because the cache is specific to the CPU architecture. A
  platform built under QEMU emulation trains several times more slowly; `aot-train.sh` waits up to
  `AOT_TRAIN_TIMEOUT` seconds (default 300) for startup.

### Measured startup

Time to `Started GatewayServerKt`, as Spring Boot logs it, and the JVM's resident set size one second after
startup. These were measured in October 2026 on the gateway before it moved to the 8.0 pipeline, and have not been
measured again since. Each run is a new JVM, and the two modes alternate. Everything ran on an Apple M4 (10 cores).

**In the container:** the image (Dragonwell 25.0.4, `linux/arm64`) against a Redis 7 container, 15 runs of each. The
machine was busy (1-minute load average 4.4 to 6.7), so the spread is wide:

| | Median | Fastest | Interquartile range | RSS (median) |
|---|---|---|---|---|
| Without the cache | 3.45 s | 2.46 s | 2.60 – 3.70 s | 524 MiB |
| With the cache | 2.53 s | 1.83 s | 2.37 – 2.77 s | 550 MiB |

**Natively on macOS:** OpenJDK 25.0.5, the image's JVM options and `cocache.backend=memory`, 7 runs of each, load
3.2 to 3.8. Here the Redis backend cannot start, because Lettuce needs the epoll or io_uring transport, so these
runs use the in-memory backend. The spread is much tighter:

| | Median | Range | RSS (median) |
|---|---|---|---|
| Without the cache | 2.41 s | 2.40 – 2.45 s | 453 MiB |
| With the cache | 1.84 s | 1.80 – 1.88 s | 519 MiB |

Both setups start about a quarter faster with the cache: 27 % on the container median, 26 % on its fastest run,
and 24 % natively. With the cache, 83 % of the classes the gateway loads come from it (13,127 of 15,884). Raw
results, the commands and the machine are in
[`docs/perf/gateway-aot-cache`](https://github.com/Ahoo-Wang/CoSec/tree/main/docs/perf/gateway-aot-cache).
