#!/usr/bin/env bash
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

# The measured pairs of the 7.x vs 8.0 comparison and how to run them. Called by .github/workflows/jmh-v7-v8.yml.
#
#   run.sh estimate|run
#
# Environment: V7_DIR, V8_DIR (worktrees with built jmhJars), RESULTS (output dir), JAVA25, INCLUDE, SCENARIOS,
# ROUNDS, FORKS, WARMUP, ITERATIONS, WARMUP_TIME, MEASUREMENT_TIME, SCRIPTS (directory of estimate.py).
#
# Each line of PAIRS is: name | 7.x module | 7.x class | 8.0 module | 8.0 class | extra 8.0 params. The scenario
# names are identical on both sides; the mapping is documented in compare.py.
set -euo pipefail

PAIRS=(
  "pipeline|cosec-core|SecurityPipelineBenchmark|e2e|PipelineBenchmark|"
  "authorization|cosec-core|SimpleAuthorizationBenchmark|engine|FlatAuthorizationBenchmark|boundary=none"
)

jar_of() { # dir module
  local jar
  jar=$(find "$1/$2/build/libs" -name '*-jmh.jar' | head -n 1)
  [ -n "$jar" ] || { echo "no jmh jar for $2 in $1" >&2; exit 1; }
  echo "$jar"
}

# Scenarios of a pair: `anonymous` exists only in the pipeline benchmark.
scenarios_of() {
  if [ "$1" = pipeline ]; then echo "$SCENARIOS"; else echo "$SCENARIOS" | tr ',' '\n' | grep -vx anonymous | paste -sd, -; fi
}

selected() { # v7 class, v8 class
  echo "$1 $2" | grep -Eq -- "$INCLUDE"
}

common_args() {
  echo -f "$FORKS" -wi "$WARMUP" -i "$ITERATIONS" -w "$WARMUP_TIME" -r "$MEASUREMENT_TIME" -prof gc -rf json
}

mode=${1:?estimate|run}
total=0
count=0
for round in $(seq 1 "$ROUNDS"); do
  [ "$mode" = estimate ] && [ "$round" -gt 1 ] && break
  for line in "${PAIRS[@]}"; do
    IFS='|' read -r name m7 c7 m8 c8 extra <<< "$line"
    selected "$c7" "$c8" || continue
    scen=$(scenarios_of "$name")
    [ -n "$scen" ] || continue
    for side in v7 v8; do
      if [ $side = v7 ]; then dir=$V7_DIR mod=$m7 cls=$c7 params="scenario=$scen"; else dir=$V8_DIR mod=$m8 cls=$c8 params="scenario=$scen $extra"; fi
      jar=$(jar_of "$dir" "$mod")
      pargs=()
      for p in $params; do pargs+=(-p "$p"); done
      if [ "$mode" = estimate ]; then
        # One run per side and round, so estimate.py's own x2 sides is right when fed this side's listing.
        [ $side = v8 ] || continue
        "$JAVA25" -jar "$jar" "\\.$cls\\." -lp 2>&1 \
          | python3 -I "$SCRIPTS/estimate.py" "$ROUNDS" "$FORKS" "$WARMUP" "$ITERATIONS" \
              "${WARMUP_TIME%s}" "${MEASUREMENT_TIME%s}" $params 2> "$RESULTS/est.err" | sed "s/^/$name: /"
        total=$((total + $(sed -n 's/^minutes=//p' "$RESULTS/est.err")))
        count=$((count + 1))
      else
        echo "::group::round $round: $name ($side, $cls)"
        # The WARN-level logback config: without it forked JVMs log at DEBUG and skew the numbers.
        "$JAVA25" -jar "$jar" "\\.$cls\\." $(common_args) "${pargs[@]}" \
          -rff "$RESULTS/$side-$name-$round.json" \
          -jvmArgsAppend "-Dlogback.configurationFile=$dir/config/logback-jmh.xml"
        echo "::endgroup::"
      fi
    done
  done
done
if [ "$mode" = estimate ]; then
  [ "$count" -gt 0 ] || { echo "::error::include '$INCLUDE' matches no pair"; exit 1; }
  echo "minutes=$total" >&2
  echo "total: about $total minutes of benchmarks"
fi
