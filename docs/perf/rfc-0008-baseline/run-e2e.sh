#!/usr/bin/env bash
# Re-runs the 7.0 end-to-end baseline of RFC 0008 §3.8 on this machine.
#
# Builds `cosec-gateway-server` from the tag (in a separate worktree), starts it on JDK 25 in front of the stub
# upstream in e2e/Stub.java (one route for every path, so CoSec authorizes each request; e2e/bench-policy.json
# lets anonymous GET /bench/** through and everything else is denied), and drives it with
# k6's constant-arrival-rate executor (e2e/load.js):
#   1. warm-up at WARMUP_RATE;
#   2. step the rate up by STEP from START (STEP_DURATION each) until a step is not sustained, then by FINE_STEP
#      from the last sustained one; the highest sustained rate is the maximum throughput. Sustained = k6 dropped
#      < 0.5 % of the iterations, failed requests < 0.1 %, and achieved rate >= 99 % of the target;
#   3. ROUNDS runs of DURATION at 70 % of that maximum, recording p99.
# Before every measured k6 run it waits until no JMH fork is running and the 1-minute load is below MAX_LOAD.
# Needs Redis on localhost:6379; the gateway uses database REDIS_DB (15) so it stays out of the way of other users.
#
# Usage: docs/perf/rfc-0008-baseline/run-e2e.sh [output-dir]
# Environment: SMOKE (start and check only), TAG (v7.0.1), JAVA (JDK 25 `java`), MAX_LOAD (1.5), START, STEP, FINE_STEP, STEP_DURATION, DURATION, ROUNDS.
set -euo pipefail

TAG=${TAG:-v7.0.1}
MAX_LOAD=${MAX_LOAD:-1.5}
WARMUP_RATE=${WARMUP_RATE:-2000}
START=${START:-5000}
STEP=${STEP:-5000}
FINE_STEP=${FINE_STEP:-1000}
STEP_DURATION=${STEP_DURATION:-30s}
DURATION=${DURATION:-60s}
ROUNDS=${ROUNDS:-3}
REDIS_DB=${REDIS_DB:-15}
GATEWAY_PORT=${GATEWAY_PORT:-18080}
STUB_PORT=${STUB_PORT:-18081}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(git rev-parse --show-toplevel)
OUT=$(mkdir -p "${1:-$ROOT/build/rfc-0008-baseline}" && cd "${1:-$ROOT/build/rfc-0008-baseline}" && pwd)
SRC=$OUT/src-$TAG
E2E=$OUT/e2e
LOG=$E2E/run.log
TARGET=http://127.0.0.1:$GATEWAY_PORT/bench/ok
mkdir -p "$E2E"

if [[ -z ${JAVA:-} ]]; then
  JAVA=$(find "$HOME/.gradle/jdks" -path '*25*/Contents/Home/bin/java' -o -path '*25*/bin/java' 2>/dev/null | head -1)
fi
[[ -x $JAVA ]] || { echo "No JDK 25 found; set JAVA=/path/to/jdk-25/bin/java" >&2; exit 1; }
[[ -n ${SMOKE:-} ]] || command -v k6 >/dev/null || { echo "k6 not found" >&2; exit 1; }

if [[ ! -d $SRC ]]; then
  git -C "$ROOT" worktree add --detach "$SRC" "$TAG"
fi
(cd "$SRC" && ./gradlew :cosec-gateway-server:installDist -q)
DIST=$SRC/cosec-gateway-server/build/install/cosec-gateway-server

load1() { sysctl -n vm.loadavg 2>/dev/null | awk '{print $2}' || awk '{print $1}' /proc/loadavg; }
forks() { ps -axo command | awk '$1 ~ /\/java$/' | grep -c ForkedMain || true; }
loadavg() { uptime | sed 's/.*load averages*: //'; }
log() { echo "$(date -u +%FT%TZ) $*" | tee -a "$LOG"; }

wait_idle() {
  until [[ $(forks) -eq 0 ]] && awk -v l="$(load1)" -v m="$MAX_LOAD" 'BEGIN { exit !(l < m) }'; do
    sleep 30
  done
}

PIDS=()
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

{
  echo "tag $TAG ($(git -C "$SRC" rev-parse HEAD))"
  "$JAVA" -version 2>&1
  k6 version 2>/dev/null || true
  sysctl -n machdep.cpu.brand_string hw.ncpu 2>/dev/null || true
  sw_vers 2>/dev/null || uname -a
} | tee -a "$LOG"

"$JAVA" "$HERE/e2e/Stub.java" "$STUB_PORT" >"$E2E/stub.log" 2>&1 &
PIDS+=($!)

# The distribution's own JVM flags (bin/cosec-gateway-server) minus JMX, GC logging and heap dumps.
# Lettuce on kqueue (macOS) cannot apply the extended TCP keepalive CoCache asks for, so it uses NIO.
# SimpleAuthorization logs every decision at DEBUG in the shipped application.yaml; that is turned down to INFO.
COSEC_JWT_SECRET=$(openssl rand -hex 32) "$JAVA" -Xms2048M -Xmx2048M -XX:MaxMetaspaceSize=256M \
  -XX:MaxDirectMemorySize=512M -Xss1m -XX:+UseZGC -Dio.lettuce.core.kqueue=false \
  -Dspring.cloud.bootstrap.enabled=true -Dspring.cloud.bootstrap.location="$DIST/config/bootstrap.yaml" \
  -Dspring.config.location="file:$DIST/config/" \
  -cp "$DIST/lib/*" me.ahoo.cosec.gateway.server.GatewayServerKt \
  --server.port="$GATEWAY_PORT" \
  --spring.data.redis.database="$REDIS_DB" \
  --spring.cloud.gateway.server.webflux.routes[0].id=bench \
  --spring.cloud.gateway.server.webflux.routes[0].uri="http://127.0.0.1:$STUB_PORT" \
  --spring.cloud.gateway.server.webflux.routes[0].predicates[0]="Path=/**" \
  --cosec.authorization.local-policy.locations="classpath:cosec-policy/*-policy.json,file:$HERE/e2e/*-policy.json" \
  --logging.level.me.ahoo.cosec.authorization.SimpleAuthorization=info \
  >"$E2E/gateway.log" 2>&1 &
PIDS+=($!)

for _ in $(seq 120); do
  [[ $(curl -s -o /dev/null -w '%{http_code}' "$TARGET") == 200 ]] && break
  sleep 1
done
[[ $(curl -s -o /dev/null -w '%{http_code}' "$TARGET") == 200 ]] || { echo "gateway not answering 200 on $TARGET" >&2; exit 1; }
log "gateway up; /bench/ok -> 200, /denied -> $(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$GATEWAY_PORT/denied")"
# SMOKE=1 stops here: checks the gateway and stub start and answer, without k6.
[[ -z ${SMOKE:-} ]] || exit 0

# run NAME RATE DURATION [TARGET]: one k6 run; prints its summary line and leaves e2e/NAME.json.
run() {
  local name=$1 rate=$2 duration=$3 target=${4:-$TARGET}
  wait_idle
  log "start $name rate $rate load $(loadavg)"
  k6 run --quiet -e RATE="$rate" -e DURATION="$duration" -e TARGET="$target" -e SUMMARY="$E2E/$name.json" \
    "$HERE/e2e/load.js" | tee -a "$LOG"
  log "end   $name rate $rate load $(loadavg)"
}

sustained() {
  jq -e --argjson rate "$2" '.metrics as $m
    | (($m.dropped_iterations.values.count // 0) < 0.005 * ($m.iterations.values.count + ($m.dropped_iterations.values.count // 0)))
    and (($m.http_req_failed.values.rate // 0) < 0.001)
    and ($m.iterations.values.rate >= $rate * 0.99)' "$E2E/$1.json" >/dev/null
}

k6 run --quiet -e RATE="$WARMUP_RATE" -e DURATION=60s -e TARGET="$TARGET" "$HERE/e2e/load.js" | tee -a "$LOG"
log "warm-up done"

max=0
for step in "$STEP" "$FINE_STEP"; do
  rate=$((max > 0 ? max + step : START))
  while :; do
    run "step-$rate" "$rate" "$STEP_DURATION"
    sustained "step-$rate" "$rate" || break
    max=$rate
    rate=$((rate + step))
  done
done
log "max sustained rate $max"
((max > 0)) || { echo "no step was sustained; lower START" >&2; exit 1; }

# The stub alone at the gateway's maximum, to show the upstream is not the bottleneck.
run "stub-at-max" "$max" "$STEP_DURATION" "http://127.0.0.1:$STUB_PORT/bench/ok"

at70=$((max * 7 / 10))
for ((round = 1; round <= ROUNDS; round++)); do
  run "p99-at-70-round$round" "$at70" "$DURATION"
done
log "done: max $max, 70 % = $at70"
