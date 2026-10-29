#!/usr/bin/env sh
#
# Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#      http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Trains a JDK 25 AOT cache (JEP 483/514/515) for cosec-gateway-server.
#
# Usage: aot-train.sh <app-home> <cache-file>
#
# Run it from the same <app-home>, with the same JDK and the same JVM options as production: the cache
# records the class path (paths and jar mtimes) and the GC, and the JVM ignores a cache that does not match.
#
# Training needs no Redis or other service: CoCache runs on its in-memory backend, so the full application
# starts, Netty listens, and a few requests run through the HTTP stack. Classes that only load later (the
# Lettuce connection path, the first authorized request) are not in the cache; they load as they would without it.
#
# Two steps (AOTMode=record, then AOTMode=create) rather than the one-step AOTCacheOutput: the training run
# is stopped with SIGTERM, and the one-step mode assembles the cache in a child JVM during that shutdown,
# so a failed assembly hides behind the expected exit status of a signalled process.
set -eu

APP_HOME=$1
CACHE=$2
CONF="$CACHE.conf"
LOG="$CACHE.train.log"
# Seconds to wait for startup; multi-platform builds train under QEMU, which is several times slower.
TIMEOUT=${AOT_TRAIN_TIMEOUT:-300}

cd "$APP_HOME"
mkdir -p logs data "$(dirname "$CACHE")"

# Training-only settings. The JWT secret is a throwaway value: build steps and their logs are not the place
# for a real one.
# An image built on top of this one inherits JDK_JAVA_OPTIONS=-XX:AOTCache=...; it must not reach the steps below.
unset JDK_JAVA_OPTIONS
export COSEC_JWT_SECRET=aot-training-only-not-a-secret-0123456789
export SPRING_DATA_REDIS_URL=redis://127.0.0.1:1
# Ephemeral HTTP and JMX ports: a multi-platform build trains every platform at once, and the training JVMs can
# share one network namespace. On fixed ports the faster platform's JVM answers the slower one's health check.
EPHEMERAL_PORTS="-Dserver.port=0 -Dcom.sun.management.jmxremote.port=0"
TRAIN_OPTS="-Dcocache.backend=memory $EPHEMERAL_PORTS"

JAVA_OPTS="-XX:AOTMode=record -XX:AOTConfiguration=$CONF $TRAIN_OPTS" bin/cosec-gateway-server >"$LOG" 2>&1 &
PID=$!

# The port comes from this JVM's own log, so the health check below can only reach this JVM.
started=false
PORT=
i=0
while [ "$i" -lt "$TIMEOUT" ]; do
  if [ -z "$PORT" ]; then
    PORT=$(sed -n 's/.*Netty started on port \([0-9][0-9]*\).*/\1/p' "$LOG" | head -n 1)
  fi
  if [ -n "$PORT" ] && curl -fs -o /dev/null "http://localhost:$PORT/actuator/health/liveness"; then
    started=true
    break
  fi
  if ! kill -0 "$PID" 2>/dev/null; then
    break
  fi
  sleep 1
  i=$((i + 1))
done

if [ "$started" != true ]; then
  echo "AOT training: the application did not start within ${TIMEOUT}s" >&2
  kill "$PID" 2>/dev/null || true
  cat "$LOG" >&2
  exit 1
fi

# Exercise the HTTP path: Netty, WebFlux, route lookup, the error handler and actuator. Only paths that cannot
# leave the container: a route to the internet would make the build depend on the network.
for path in /actuator/health /actuator/health/liveness /cosec-auth/aot-training /aot-training; do
  curl -s -o /dev/null "http://localhost:$PORT$path" || true
done

kill -TERM "$PID"
# A JVM stopped by SIGTERM exits with 143; the configuration file is what matters.
wait "$PID" || true
if [ ! -s "$CONF" ]; then
  echo "AOT training: no AOT configuration was recorded" >&2
  cat "$LOG" >&2
  exit 1
fi

JAVA_OPTS="-XX:AOTMode=create -XX:AOTConfiguration=$CONF -XX:AOTCache=$CACHE" bin/cosec-gateway-server >>"$LOG" 2>&1 || {
  cat "$LOG" >&2
  exit 1
}

# Prove the production flags can map the cache. AOTMode=on fails instead of falling back; it is used here
# only. spring.context.exit stops the JVM after the context refresh, so this takes a second; the refresh creates
# the CoCache backend, which TRAIN_OPTS keeps in memory, as there is no Redis here.
JAVA_OPTS="-XX:AOTMode=on -XX:AOTCache=$CACHE -Dspring.context.exit=onRefresh $TRAIN_OPTS" bin/cosec-gateway-server >>"$LOG" 2>&1 || {
  echo "AOT training: the cache cannot be mapped with the runtime options" >&2
  cat "$LOG" >&2
  exit 1
}

rm -f "$CONF"
rm -rf logs/* data/*
echo "AOT training: $(wc -c <"$CACHE") bytes written to $CACHE"
