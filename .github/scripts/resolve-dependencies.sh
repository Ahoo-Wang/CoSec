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

# Resolves a checkout's dependencies, retried, before the job builds or tests anything.
#
# Usage: resolve-dependencies.sh [project-dir [gradle arguments...]]     (default: the current directory)
#   The arguments are those the job's own build passes to Gradle that decide what it configures (a -P property, say).
#
# CI downloads fail now and then for a reason that is not the build's: the plugin portal or Maven Central answers 429 or a 404
# for an artifact that exists, or a restored Gradle cache names a jar it does not hold (the jmh workflows' own finding, #1490).
# A re-run of the job costs a runner and, on an armed pull request, its place in the queue. This step downloads, and does
# nothing else: `resolveDependencies` (.github/gradle/resolve-dependencies.init.gradle.kts) resolves the configurations, and
# Gradle resolves the plugins' classpath and fetches its own distribution on the way. It never compiles, tests or measures,
# so a retry of it never repeats work that could change a result; the job's own steps run once, as before.
#
# Attempt 1 is a plain resolution. Later attempts wait (20 s, 40 s, 60 s: a rate limit is not helped by asking at once) and
# `--refresh-dependencies`, which asks the repositories again whatever the Gradle home remembers, so a "not found" it
# cached from a bad answer, or an index that names a jar it lacks, does not decide the retry. Gradle's own patience with a
# repository is raised for this step (more tentatives per download, a longer first pause).
# RESOLVE_ATTEMPTS and RESOLVE_PAUSE (seconds, per attempt number) override the bounds; a test sets them to 0.
set -uo pipefail

ATTEMPTS=${RESOLVE_ATTEMPTS:-4}
PAUSE=${RESOLVE_PAUSE:-20}
INIT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../gradle" && pwd)/resolve-dependencies.init.gradle.kts"
cd "${1:-.}" || exit 2
shift || true

for attempt in $(seq 1 "$ATTEMPTS"); do
  refresh=()
  [ "$attempt" -gt 1 ] && refresh=(--refresh-dependencies)
  echo "::group::Resolve dependencies in $PWD, attempt $attempt of $ATTEMPTS"
  ./gradlew ${refresh[@]+"${refresh[@]}"} \
    -Dorg.gradle.internal.repository.max.tentatives=5 -Dorg.gradle.internal.repository.initial.backoff=1000 \
    -I "$INIT" resolveDependencies "$@" --no-daemon --no-configuration-cache --max-workers=2 --console=plain
  status=$?
  echo "::endgroup::"
  if [ "$status" -eq 0 ]; then
    [ "$attempt" -gt 1 ] && echo "::warning::Dependencies resolved on attempt $attempt of $ATTEMPTS: a download failed before. Look for 'Could not GET', 'Could not find' or 'status code' in the earlier attempts of this step."
    exit 0
  fi
  if [ "$attempt" -lt "$ATTEMPTS" ]; then
    echo "::warning::Resolving dependencies failed (attempt $attempt of $ATTEMPTS, exit $status); asking again in $((attempt * PAUSE)) s."
    sleep $((attempt * PAUSE))
  fi
done
echo "::error::Dependencies did not resolve in $ATTEMPTS attempts: see the 'Could not' lines above for the repository and the answer."
exit 1
