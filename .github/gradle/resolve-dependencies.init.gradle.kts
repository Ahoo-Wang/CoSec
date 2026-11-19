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

// An init script for CI (`./gradlew -I .github/gradle/resolve-dependencies.init.gradle.kts resolveDependencies`): resolves what
// a build downloads, and nothing else (no compilation, no test, no benchmark), so that a download that fails is retried
// by `.github/scripts/resolve-dependencies.sh` before the build, not by a re-run of a whole job. The plugins' classpath is
// resolved while Gradle configures the build, before this task starts. Never loaded by a developer's build.
// What a download that failed says, anywhere in the chain of causes: the repository's answer or the connection's. Anything
// else (a configuration that cannot be resolved by itself, such as the coverage aggregation, whose dependencies have no version
// until it is used) is not a download, and is left to the build that uses it.
val downloadFailure = Regex(
    "Could not (GET|HEAD|get resource)|Could not find \\S+:\\S+:[0-9A-Za-z]|status code|Read timed out|Connect|reset|Premature end|UnknownHost|Remote host|handshake",
    RegexOption.IGNORE_CASE,
)

fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }.take(20)

// What a build downloads: the classpaths its compilations and its tests use, the Kotlin compiler's, the annotation processors
// and the benchmark generator. Not the tools of tasks a CI job does not run (detekt, Dokka, the ABI validator, the commonizer):
// resolving them would fail a step for an artifact nothing in that job asks for. `-PresolveConfigurations=<regex>` replaces it.
val resolved: Regex = (providers.gradleProperty("resolveConfigurations").orNull
    ?: "(.*(CompileClasspath|RuntimeClasspath)|annotationProcessor|kapt.*|kotlinCompiler.*|kotlinBuildTools.*|jmh)")
    .toRegex()

allprojects {
    tasks.register("resolveDependencies") {
        group = "ci"
        description = "Resolves the configurations a build downloads for, which downloads the dependencies it needs."
        // It reads the project's configurations when it runs.
        notCompatibleWithConfigurationCache("resolves the configurations of its project at execution time")
        doLast {
            val failed = configurations.filter { it.isCanBeResolved && resolved.matches(it.name) }.mapNotNull { configuration ->
                try {
                    configuration.incoming.artifacts.artifacts
                    null
                } catch (failure: Exception) {
                    val messages = failure.causes().mapNotNull { it.message }.flatMap { it.lineSequence() }.toList()
                    val label = "${project.path}:${configuration.name}"
                    if (messages.any { downloadFailure.containsMatchIn(it) }) {
                        "$label: " + messages.filter { downloadFailure.containsMatchIn(it) }.take(2).joinToString(" / ") { it.trim() }
                    } else {
                        logger.lifecycle("$label is not resolvable by itself, left to the build that uses it: ${messages.firstOrNull()}")
                        null
                    }
                }
            }
            if (failed.isNotEmpty()) {
                throw GradleException("${failed.size} configuration(s) could not download what they need:\n" + failed.joinToString("\n"))
            }
        }
    }
}
