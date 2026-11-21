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

# The key of the dependency cache (.github/scripts/README.md, "The dependency cache"): what determines which files a resolution
# downloads, and nothing else, so that two checkouts that would download the same files name the same cache.
#
#   versions   the dependency and plugin versions (gradle/libs.versions.toml) and the Gradle distribution (the wrapper
#              properties): a change of either is a change of the files.
#   repos      where they are read from: the settings script and every line of a build script that names a repository or a
#              plugin source. Gradle's metadata cache is kept per repository, so files read through another set of
#              repositories are not found by a build that asks this one (#1730): a cache never restores across a change of
#              `repos`, and falls back, across a change of `versions`, only to one written for the same `repos`.
#
# Prints `key=...` and `restore-keys=...` for $GITHUB_OUTPUT; run in the root of a checkout.
set -euo pipefail

hash() { sha256sum | cut -c1-16; }
repos=$({
  cat settings.gradle.kts
  find . -name '*.gradle.kts' -not -path './node_modules/*' -not -path '*/build/*' -not -path './.git/*' -not -path './typescript/*' \
    | LC_ALL=C sort | xargs grep -hE 'mavenCentral|mavenLocal|gradlePluginPortal|maven *[({]|exclusiveContent|pluginManagement|repositories' || true
} | hash)
versions=$(cat gradle/libs.versions.toml gradle/wrapper/gradle-wrapper.properties | hash)
echo "key=gradle-dependencies-v1-$repos-$versions"
echo "restore-keys=gradle-dependencies-v1-$repos-"
