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
# Usage: [RESOLVE_CENTRAL_FALLBACK=on] [RESOLVE_TASKS="<tasks>"] resolve-dependencies.sh [project-dir [gradle arguments...]]     (default: the current directory)
#   RESOLVE_TASKS names what to resolve: `resolveDependencies` (every project) unless a job needs only some, such as
#   `:runtime:resolveDependencies :control:resolveDependencies`.
#   RESOLVE_CENTRAL_FALLBACK=on installs `.github/gradle/central-fallback.init.gradle.kts` into the Gradle home for the rest of the job:
#   a second host for what Maven Central and the plugin portal both answer "not found" for (below). Off unless asked, and never asked
#   by a job that publishes, and no workflow asks for it yet (.github/scripts/README.md): what it ships is built from what the repositories of the build serve.
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
# A restored cache can also be wrong in a way no repository can answer. `Could not find <file>.jar (<group>:<name>:<version>)`
# raised from the cache lookup is Gradle reading the cache's index, not a repository's answer: the index names the module,
# and says the file is not there. Asking again, even with --refresh-dependencies, reads the same index, so this mode removes
# `caches/modules-2` at once, without the pause and without the attempt that cannot help. It happened on 2026-11-20: #1730 put
# Maven Central ahead of the plugin portal, which changes the repository the plugins' jars are read under, and the required
# check's `Test host` job, whose restored cache held the old answers, failed on every run from then on until its cache was
# removed. A change of which repository an artifact is read from comes with the removal of the caches that remember the old
# answer, or with this step (.github/scripts/README.md).
#
# A "Could not find" for a file that exists is a 404 from the repository, not a throttle, and no pause and no cache removal helped
# it. On 2026-11-20 it failed six attempts over 12 minutes four times (CodeQL on #1745 and #1765, two mutation shards of run
# 44750934771), for POMs Central serves (the Kotlin plugin's BOM, `jmh-core`), from Central and from the plugin portal alike,
# while other runners of the same run passed; after a failed attempt this step now asks each URL Gradle searched itself and
# prints the answer (diagnose below), so the next failure says whether it was a 404 or something else. Where a job asks for
# it, the fallback repository answers for those files.
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
read -r -a TASKS <<< "${RESOLVE_TASKS:-resolveDependencies}"
# The cache's own answer, not a repository's: the file is "not found" though the index knows the module.
STALE_INDEX='Could not find [^ ()]+\.(jar|aar|klib|zip) \('
modules="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2"
removed=0
log=$(mktemp)
trap 'rm -f "$log"' EXIT
read -r -a PAUSES <<< "${RESOLVE_PAUSES:-30 60 120 180 240}"
GRADLE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../gradle" && pwd)"
INIT="$GRADLE_DIR/resolve-dependencies.init.gradle.kts"
cd "${1:-.}" || exit 2
shift || true

if [ "${RESOLVE_CENTRAL_FALLBACK:-off}" = on ]; then
  mkdir -p "${GRADLE_USER_HOME:-$HOME/.gradle}/init.d"
  cp "$GRADLE_DIR/central-fallback.init.gradle.kts" "${GRADLE_USER_HOME:-$HOME/.gradle}/init.d/"
  echo "Installed the Maven Central fallback repository for this job's Gradle runs."
fi

# What each file Gradle searched for answers now, from this runner: the log of a failed resolution says "Could not find", never
# the status, and a 404 for a file that exists, a 429 and a block are different failures with different remedies. Some
# failures name no URL: a plugin that is "not found in any of the following sources" gives its marker's coordinates and the
# names of its repositories (CodeQL, run 44827683777), and the resolve task's "Could not find <g>:<a>:<v>" the module's. For
# those, the POM is asked of Maven Central and of the plugin portal, which are what the build's repositories are.
files() {
  grep -Eo 'https://[^ ]+\.(pom|module|jar)' "$log"
  {
    grep -Eo "plugin artifact '[^']+'" "$log" | sed "s/^plugin artifact '//; s/'\$//"
    grep -Eo 'Could not find [^ ()]+:[^ ()]+:[0-9A-Za-z][^ ()]*' "$log" | sed 's/^Could not find //; s/\.$//'
  } | sort -u | while IFS=: read -r group artifact version; do
    path="${group//.//}/$artifact/$version/$artifact-$version.pom"
    echo "https://repo.maven.apache.org/maven2/$path"
    echo "https://plugins.gradle.org/m2/$path"
  done
}

# The status, and the headers that say which edge of which CDN answered: `cf-ray` ends in the edge's airport code.
diagnose() {
  files | sort -u | head -8 | while read -r url; do
    echo "  $(curl -sS -o /dev/null -I --max-time 20 -w '%{http_code}' "$url" 2>&1) $url"
    curl -sSI --max-time 20 "$url" 2>/dev/null | grep -iE '^(server|via|x-served-by|x-cache|x-cache-hits|cf-ray|cf-cache-status|retry-after|age):' | sed 's/^/      /' | tr -d '\r'
  done
}

for attempt in $(seq 1 "$ATTEMPTS"); do
  refresh=()
  [ "$attempt" -gt 1 ] && refresh=(--refresh-dependencies)
  if [ "$attempt" -ge 3 ] && [ "$removed" -eq 0 ]; then
    echo "Removing $modules: the retry resolves from the repositories alone."
    rm -rf "$modules"
    removed=1
  fi
  echo "::group::Resolve dependencies in $PWD, attempt $attempt of $ATTEMPTS"
  ./gradlew ${refresh[@]+"${refresh[@]}"} \
    -Dorg.gradle.internal.repository.max.tentatives=5 -Dorg.gradle.internal.repository.initial.backoff=1000 \
    -I "$INIT" "${TASKS[@]}" "$@" --no-daemon --no-configuration-cache --max-workers=2 --console=plain 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  echo "::endgroup::"
  if [ "$status" -eq 0 ]; then
    [ "$attempt" -gt 1 ] && echo "::warning::Dependencies resolved on attempt $attempt of $ATTEMPTS: a download failed before. Look for 'Could not GET', 'Could not find' or 'status code' in the earlier attempts of this step."
    exit 0
  fi
  if [ "$attempt" -lt "$ATTEMPTS" ] && [ "$removed" -eq 0 ] && grep -Eq "$STALE_INDEX" "$log"; then
    echo "::warning::A restored Gradle cache names a file it does not hold (attempt $attempt of $ATTEMPTS): removing $modules and asking again at once."
    rm -rf "$modules"
    removed=1
  elif [ "$attempt" -lt "$ATTEMPTS" ]; then
    echo "What the repositories answer the files this attempt searched for, from this runner:"
    diagnose
    pause=${PAUSES[$((attempt - 1))]:-${PAUSES[${#PAUSES[@]} - 1]}}
    echo "::warning::Resolving dependencies failed (attempt $attempt of $ATTEMPTS, exit $status); asking again in $pause s."
    sleep "$pause"
  fi
done
diagnose
echo "::error::Dependencies did not resolve in $ATTEMPTS attempts: see the 'Could not' lines above for the repository and the answer."
exit 1
