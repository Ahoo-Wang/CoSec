#!/usr/bin/env bash
# Re-runs the 7.0 JMH baseline of RFC 0008 §3.8 on this machine.
#
# Builds `cosec-core`'s JMH jar from the tag (in a separate worktree, so the current checkout is untouched) and runs
# SimpleAuthorizationBenchmark and RateLimitingAuthorizationBenchmark with the RFC 0002 §7.2 settings: JDK 25,
# 5 warm-up and 10 measurement iterations of 10 s, 3 forks, -prof gc, three rounds alternating the order of the two
# benchmarks. Before every run it waits until no other JMH fork is running and the 1-minute load is below MAX_LOAD.
#
# The tag predates the -PjmhJavaVersion/-PjmhFork/... Gradle properties, so the jar is run directly with JMH's own
# options instead of through `./gradlew jmh`.
#
# Usage: docs/perf/rfc-0008-baseline/run-jmh.sh [output-dir]
# Environment: TAG (v7.0.1), ROUNDS (3), MAX_LOAD (1.5), JAVA (a JDK 25 `java`; defaults to Gradle's toolchain one).
set -euo pipefail

TAG=${TAG:-v7.0.1}
ROUNDS=${ROUNDS:-3}
MAX_LOAD=${MAX_LOAD:-1.5}
ROOT=$(git rev-parse --show-toplevel)
OUT=$(mkdir -p "${1:-$ROOT/build/rfc-0008-baseline}" && cd "${1:-$ROOT/build/rfc-0008-baseline}" && pwd)
SRC=$OUT/src-$TAG
LOG=$OUT/load.log

if [[ -z ${JAVA:-} ]]; then
  JAVA=$(find "$HOME/.gradle/jdks" -path '*25*/Contents/Home/bin/java' -o -path '*25*/bin/java' 2>/dev/null | head -1)
fi
[[ -x $JAVA ]] || { echo "No JDK 25 found; set JAVA=/path/to/jdk-25/bin/java" >&2; exit 1; }

if [[ ! -d $SRC ]]; then
  git -C "$ROOT" worktree add --detach "$SRC" "$TAG"
fi
(cd "$SRC" && ./gradlew :cosec-core:jmhJar -q)
JAR=$(ls "$SRC"/cosec-core/build/libs/cosec-core-*-jmh.jar)

load1() { sysctl -n vm.loadavg 2>/dev/null | awk '{print $2}' || awk '{print $1}' /proc/loadavg; }
forks() { ps -axo command | awk '$1 ~ /\/java$/' | grep -c ForkedMain || true; }

wait_idle() {
  until [[ $(forks) -eq 0 ]] && awk -v l="$(load1)" -v m="$MAX_LOAD" 'BEGIN { exit !(l < m) }'; do
    sleep 30
  done
}

run() {
  local bench=$1 round=$2
  wait_idle
  echo "$(date -u +%FT%TZ) start $bench round $round load $(uptime | sed 's/.*load averages*: //')" | tee -a "$LOG"
  "$JAVA" -jar "$JAR" "\\.$bench\\." \
    -wi 5 -i 10 -f 3 -w 10s -r 10s -bm thrpt -t 1 -prof gc \
    -jvmArgsAppend "-Dlogback.configurationFile=$SRC/config/logback-jmh.xml" \
    -rf json -rff "$OUT/jmh-$bench-round$round.json" >"$OUT/jmh-$bench-round$round.log"
  echo "$(date -u +%FT%TZ) end   $bench round $round load $(uptime | sed 's/.*load averages*: //')" | tee -a "$LOG"
}

{
  echo "tag $TAG ($(git -C "$SRC" rev-parse HEAD))"
  "$JAVA" -version 2>&1
  sysctl -n machdep.cpu.brand_string hw.ncpu 2>/dev/null || true
  sw_vers 2>/dev/null || uname -a
} | tee -a "$LOG"

for ((round = 1; round <= ROUNDS; round++)); do
  if ((round % 2)); then
    run SimpleAuthorizationBenchmark "$round"
    run RateLimitingAuthorizationBenchmark "$round"
  else
    run RateLimitingAuthorizationBenchmark "$round"
    run SimpleAuthorizationBenchmark "$round"
  fi
done

# Trimmed like docs/perf/7.0-vs-6.1.0: throughput with raw data, and bytes allocated per operation.
for f in "$OUT"/jmh-*-round*.json; do
  jq --arg round "${f##*round}" '[.[] | {round: ($round | rtrimstr(".json") | tonumber), benchmark, params,
    primaryMetric: (.primaryMetric | {score, scoreError, scoreConfidence, scoreUnit, rawData}),
    "gc.alloc.rate.norm": (.secondaryMetrics["gc.alloc.rate.norm"] | {score, scoreError, scoreConfidence, scoreUnit})}]' "$f"
done | jq -s 'add | sort_by(.round, .benchmark, .params.scenario)' >"$OUT/jmh-${TAG#v}-jdk25.json"
echo "Done: $OUT"
