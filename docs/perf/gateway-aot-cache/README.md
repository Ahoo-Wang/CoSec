# Gateway server startup with a JDK 25 AOT cache

Startup of `cosec-gateway-server` with and without the AOT cache (JEP 483/514/515) that its image trains. How the
image builds and uses the cache is in [`wiki/operations/deployment.md`](../../../wiki/operations/deployment.md#aot-cache).

| File | Contents |
|---|---|
| `native-macos.csv` | One row per run: run number, mode (`none` / `aot`), seconds to `Started` as Spring Boot logs it, total process seconds at that point, RSS in KiB one second after `Started`, 1-minute load average when the run began |

## Results

7 runs of each mode, alternating `none`, `aot`, `none`, …, each a new JVM:

| | `Started` median | range | process median | range | RSS median | range |
|---|---|---|---|---|---|---|
| Without the cache | 2.413 s | 2.397 – 2.450 s | 2.707 s | 2.704 – 2.745 s | 453 MiB | 449 – 460 MiB |
| With the cache | 1.839 s | 1.796 – 1.883 s | 2.098 s | 2.053 – 2.145 s | 519 MiB | 508 – 529 MiB |
| Change | −23.8 % | | −22.5 % | | +66 MiB | |

- The two ranges do not overlap, so the gain is larger than the noise even on this loaded machine.
- **How full the cache is.** Counted with `-Xlog:class+load` from launch until the JVM was stopped one second after
  `Started`: with the cache, 13,127 of 15,884 loaded classes (83 %) came from it; without it, 1,459 of 15,866 came from
  the JDK's default CDS archive. The rest are classes the training run never loaded (the Redis connection path, for
  one) and classes the cache skips: training logged 240 of those: 165 from unsupported locations (dynamic proxies,
  classes Spring defines at runtime), 47 from the signed Bouncy Castle jar, 15 JFR event classes, and 13 that failed
  verification or were not fully loaded.
- RSS grows because it counts the mapped, file-backed pages of the 104 MiB cache; heap use does not change.
- These are startup numbers only. The cache does not change peak JIT throughput, and nothing here measures
  steady-state request handling.

## Conditions

- Apple M4, 10 cores, 16 GiB, macOS 27.0.1.
- OpenJDK 25.0.5 (Homebrew build 25.0.5), the code compiled for 17 as usual.
- `main` at `165be029`, plus the bean rename in `CoSecRedisRateLimiterAutoConfiguration` that lets the gateway start
  (on `main` it fails with a duplicate `redisRateLimiter` bean).
- The distribution from `./gradlew :cosec-gateway-server:installDist`, run through `bin/cosec-gateway-server`, so with
  the image's JVM options: ZGC, `-Xms2048M -Xmx2048M`, JMX on, Spring Cloud bootstrap on.
- `-Dcocache.backend=memory` and an unreachable Redis URL. **Natively on macOS, not in the container**: on macOS the
  Redis backend cannot start (Lettuce needs the epoll or io_uring transport for its keepalive options), so these runs
  use the in-memory backend that training also uses.
- The cache was trained by `cosec-gateway-server/aot-train.sh` from the same directory: 104 MiB.
- "Cold" means a new JVM per run; the OS file cache was warm, as it is when a container restarts on the same host.
- Ran 2026-10-21 08:41–08:43 UTC.
- **The machine was not idle.** The 1-minute load average was 3.2 to 3.8 during the runs (a Docker VM, the Claude
  desktop app and other sessions were running). Alternating the two modes spreads that load over both.

## Commands

```bash
./gradlew :cosec-gateway-server:installDist
cp -R cosec-gateway-server/build/install/cosec-gateway-server "$WORK/"
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
cosec-gateway-server/aot-train.sh "$WORK/cosec-gateway-server" "$WORK/aot/app.aot"
```

Each run, in `$WORK/cosec-gateway-server`, with `O=` for `none` and `O=-XX:AOTCache=$WORK/aot/app.aot` for `aot`:

```bash
export COSEC_JWT_SECRET=measure-only-0123456789abcdef SPRING_DATA_REDIS_URL=redis://127.0.0.1:1
JAVA_OPTS="$O -Dcocache.backend=memory" bin/cosec-gateway-server > m.log 2>&1 &
# wait for "Started GatewayServerKt" in m.log, sleep 1, read `ps -o rss=` of the JVM, then kill it
```
