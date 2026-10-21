# Gateway server startup with a JDK 25 AOT cache

Startup of `cosec-gateway-server` with and without the AOT cache (JEP 483/514/515) that its image trains. How the
image builds and uses the cache is in [`wiki/operations/deployment.md`](../../../wiki/operations/deployment.md#aot-cache).

| File | Contents |
|---|---|
| `container-15.csv` | The image, 15 runs of each mode, with Redis |
| `container-7.csv` | The image, an earlier 7 runs of each mode, with Redis (load rising from 3.6 to 6.3; kept for completeness) |
| `native-macos.csv` | The `installDist` distribution natively on macOS, 7 runs of each mode, `cocache.backend=memory` |
| `measure-container.sh` | The script that produced the container files |

Every CSV has one row per run: run number, mode (`none` / `aot`), seconds to `Started` as Spring Boot logs it, total
process seconds at that point, RSS in KiB one second after `Started`, and the 1-minute load average when the run
began. The modes alternate (`none`, `aot`, `none`, …), and every run is a new JVM.

## Results

### Container

The image, with a fresh container per run against a Redis 7 container on the same Docker network. The cache is
turned off with `-e JDK_JAVA_OPTIONS=`. No run logged an `[aot]` line, so every `aot` run used the cache.

| 15 runs each | `Started` median | fastest | interquartile range | process median | RSS median |
|---|---|---|---|---|---|
| Without the cache | 3.450 s | 2.463 s | 2.597 – 3.695 s | 4.089 s | 524 MiB |
| With the cache | 2.531 s | 1.826 s | 2.368 – 2.771 s | 3.085 s | 550 MiB |
| Change | −26.6 % | −25.9 % | | −24.6 % | +26 MiB |

**The machine was busy** (load average 4.4 to 6.7, from a screen-sharing session), and the spread is wide: one run
without the cache took 4.08 s, one with it 3.10 s. Both the median and the fastest run improve by about a quarter,
which agrees with the native runs below. The earlier 7-run set (`container-7.csv`, load rising from 3.6 to 6.3) is
too noisy to show anything: medians of 2.562 s and 2.458 s, with ranges of 2.45–4.58 s and 1.80–3.12 s. Repeat on an
idle machine before quoting absolute numbers.

### Native

The same distribution, run directly on macOS, which gives a much tighter spread. These runs use
`cocache.backend=memory` because on macOS the Redis backend cannot start: Lettuce needs the epoll or io_uring
transport for its keepalive options.

| 7 runs each | `Started` median | range | process median | range | RSS median | range |
|---|---|---|---|---|---|---|
| Without the cache | 2.413 s | 2.397 – 2.450 s | 2.707 s | 2.704 – 2.745 s | 453 MiB | 449 – 460 MiB |
| With the cache | 1.839 s | 1.796 – 1.883 s | 2.098 s | 2.053 – 2.145 s | 519 MiB | 508 – 529 MiB |
| Change | −23.8 % | | −22.5 % | | +66 MiB | |

The two ranges do not overlap.

### What the numbers mean

- **RSS grows** because it counts the mapped, file-backed pages of the 104 MiB cache. Heap use does not change.
- **How full the cache is.** Counted natively with `-Xlog:class+load`, from launch until the JVM was stopped one
  second after `Started`:
  - With the cache, 13,127 of 15,884 loaded classes (83 %) came from it.
  - Without it, 1,459 of 15,866 came from the JDK's default CDS archive.
  - The rest are classes the training run never loaded (the Redis connection path, for one) and classes the cache
    skips. Training logged 240 skipped classes:
    - 165 from unsupported locations (dynamic proxies, classes Spring defines at runtime)
    - 47 from the signed Bouncy Castle jar
    - 15 JFR event classes
    - 13 that failed verification or were not fully loaded
- **Startup only.** The cache does not change peak JIT throughput, and nothing here measures steady-state request
  handling.

## Conditions

- Apple M4, 10 cores, 16 GiB, macOS 27.0.1. Docker Desktop's Linux VM: 10 CPUs, 7.7 GiB, kernel 7.0.14-linuxkit,
  `linux/arm64`.
- Container: the image built from this branch with `docker build -t cosec-gateway:aot-local cosec-gateway-server`
  after `./gradlew :cosec-gateway-server:installDist`, so Alibaba Dragonwell 25.0.4.0.4+7-GA. Measured
  2026-10-21 09:16–09:18 UTC (7 runs) and 09:18–09:22 UTC (15 runs).
- Native: OpenJDK 25.0.5 (Homebrew), `bin/cosec-gateway-server` from `installDist`, so with the image's JVM options
  (ZGC, `-Xms2048M -Xmx2048M`, JMX on, Spring Cloud bootstrap on), and an unreachable Redis URL. Its cache was
  trained by `cosec-gateway-server/aot-train.sh` from the same directory. Measured 2026-10-21 08:41–08:43 UTC, load
  3.2 to 3.8.
- Code: `main` with the `redisRateLimiter` bean rename of #979, which the gateway needs in order to start at all.
- "Cold" means a new JVM per run. The OS file cache was warm, as it is when a container restarts on the same host.

## Commands

Container, from the repository root (the script expects a Docker network `cosec-aot` with a Redis container
`cosec-aot-redis` on it):

```bash
./gradlew :cosec-gateway-server:installDist
docker build -t cosec-gateway:aot-local cosec-gateway-server
docker network create cosec-aot
docker run -d --name cosec-aot-redis --network cosec-aot redis:7
docs/perf/gateway-aot-cache/measure-container.sh cosec-gateway:aot-local 15
```

Native, with `O=` for `none` and `O=-XX:AOTCache=$WORK/aot/app.aot` for `aot`:

```bash
cp -R cosec-gateway-server/build/install/cosec-gateway-server "$WORK/"
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
cosec-gateway-server/aot-train.sh "$WORK/cosec-gateway-server" "$WORK/aot/app.aot"
cd "$WORK/cosec-gateway-server"
export COSEC_JWT_SECRET=measure-only-0123456789abcdef SPRING_DATA_REDIS_URL=redis://127.0.0.1:1
JAVA_OPTS="$O -Dcocache.backend=memory" bin/cosec-gateway-server > m.log 2>&1 &
# wait for "Started GatewayServerKt" in m.log, sleep 1, read `ps -o rss=` of the JVM, then kill it
```
