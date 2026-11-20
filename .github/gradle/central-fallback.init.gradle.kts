/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// An init script for CI, installed into `$GRADLE_USER_HOME/init.d` by `.github/scripts/resolve-dependencies.sh` when a job asks
// for it (`RESOLVE_CENTRAL_FALLBACK=on`); never loaded by a developer's build and never installed by a job that publishes.
//
// It appends one repository, last, to the plugin repositories and to every project's: Google's read-only copy of Maven
// Central. Gradle asks it only for what every repository before it (Maven Central, then the plugin portal) answered "not
// found" for. That happens: on 2026-11-20, for minutes at a time and on one runner while others passed, both answered
// `Could not find` for POMs that Central serves (the Kotlin and Dokka plugins' BOMs, `jmh-core`), on every one of six attempts
// 15 minutes apart. A second host that holds the same files does not share that answer. It is installed for the whole job,
// not for the resolving attempts alone, because Gradle's module cache is kept per repository: what the resolving step
// found in this repository is found there again by the build steps after it only if they ask the same repositories.
//
// What it does not change: the versions (every one is pinned in `gradle/libs.versions.toml`), what a repository earlier in the
// order answers, or a failing job: an artifact no repository has still fails the job. What it cannot cover: an artifact only the
// plugin portal hosts (the markers and jars of the Nexus publish plugin and the JMH plugin) is not on Central or its copy.
val fallback = uri("https://maven-central.storage-download.googleapis.com/maven2/")

gradle.settingsEvaluated {
    pluginManagement.repositories.maven {
        name = "CentralFallback"
        url = fallback
    }
}

gradle.afterProject {
    repositories.maven {
        name = "CentralFallback"
        url = fallback
    }
}
