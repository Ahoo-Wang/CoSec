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
# A "configuration cache state could not be cached" error in a build can be a failed download underneath: read the cause
# below it (`Could not find` or `Could not GET`, with the repository), and look at this step's earlier attempts.
#
# Attempt 1 is a plain resolution. Later attempts wait first, 30, 60, 120, 180 and 240 s (630 s, so about 10 minutes of
# waiting and, with the attempts themselves, 15 at the worst): a repository that throttles a runner keeps doing so for
# minutes, not seconds. Two failed runs measured it: Maven Central answered 429 for one POM for at least 4 min 50 s, on
# every attempt (jmh-ab run 44642549624), and the plugin portal answered a 404 for three POMs for 2 min 30 s (CodeQL run
# 44624319099), where four attempts over 2 to 4 minutes had not been enough, and a re-run passed minutes later.
# Attempt 2 adds `--refresh-dependencies`, which asks the repositories again about what the Gradle home remembers. Before
# attempt 3, once, the dependency cache of the Gradle home (`caches/modules-2`: the jars and the index that names them) is
# removed, because a restored cache can hold an index that names jars it lacks, and Gradle then answers "Could not find" for
# them without asking any repository, however often it is asked (jmh-ab.yml, #1490); the retries resolve from the repositories
# alone. Once, because what attempt 3 onwards downloads is sound, and removing it again would only fetch it again from a repository
# that is throttling. Gradle's own patience with a repository is raised for this step (more tentatives per download, a longer
# first pause). RESOLVE_ATTEMPTS (default 6) and RESOLVE_PAUSES (seconds, one per wait, the last repeating; default
# "30 60 120 180 240") override the bounds; a test sets the pauses to 0.
set -uo pipefail

ATTEMPTS=${RESOLVE_ATTEMPTS:-6}
read -r -a PAUSES <<< "${RESOLVE_PAUSES:-30 60 120 180 240}"
INIT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../gradle" && pwd)/resolve-dependencies.init.gradle.kts"
cd "${1:-.}" || exit 2
shift || true

for attempt in $(seq 1 "$ATTEMPTS"); do
  refresh=()
  [ "$attempt" -gt 1 ] && refresh=(--refresh-dependencies)
  if [ "$attempt" -eq 3 ]; then
    modules="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2"
    echo "Removing $modules: the retry resolves from the repositories alone."
    rm -rf "$modules"
  fi
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
    pause=${PAUSES[$((attempt - 1))]:-${PAUSES[${#PAUSES[@]} - 1]}}
    echo "::warning::Resolving dependencies failed (attempt $attempt of $ATTEMPTS, exit $status); asking again in $pause s."
    sleep "$pause"
  fi
done
echo "::error::Dependencies did not resolve in $ATTEMPTS attempts: see the 'Could not' lines above for the repository and the answer."
exit 1
