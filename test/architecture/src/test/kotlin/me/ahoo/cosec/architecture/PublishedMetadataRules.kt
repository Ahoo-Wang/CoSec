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

package me.ahoo.cosec.architecture

/**
 * What a published module may tell a build that depends on it (RFC 0011 §5). Each rule takes the parsed metadata and
 * answers the violations, so a test can hold it to a deliberately wrong sample as well as to the real files.
 *
 * - No platform: an application that adds a CoSec module changes its own versions by what that module needs, never by a
 *   BOM CoSec imports (a platform in the metadata raises every version it manages, for the whole build).
 * - Every dependency has a version: a Maven build, which does not read the Gradle platform, cannot resolve one without.
 * - No test or build tool.
 * - The POM brings at run time what the Gradle `runtimeElements` variant brings, no more: Maven reads one POM scope for
 *   both compile and run time, so a dependency only needed to compile (`compileOnlyApi`) must be `provided` in it.
 */
object PublishedMetadataRules {
    /** Groups, or `group:name`s, of the test and build tools no published module may name. */
    val TOOLS: List<String> = listOf(
        "org.openjdk.jmh:",
        "io.mockk:",
        "com.tngtech.archunit:",
        "io.projectreactor.tools:blockhound",
        "io.gitlab.arturbosch.detekt:",
        "com.networknt:json-schema-validator",
        "me.ahoo.test:",
    )

    /** The build's own platform, which no published module may name. */
    const val INTERNAL_PLATFORM = "cosec-dependencies"

    private val PLATFORM_CATEGORIES = setOf("platform", "enforced-platform")

    fun noPlatform(metadata: PublishedMetadata): List<String> =
        (metadata.pomDependencies + metadata.pomManaged).filter { it.scope == "import" || it.type == "pom" }
            .map { "${metadata.artifact}: the POM imports or depends on the platform ${it.id}" } +
            metadata.variants.flatMap { (variant, dependencies) ->
                dependencies.filter { it.category in PLATFORM_CATEGORIES }
                    .map { "${metadata.artifact}: variant $variant depends on the platform ${it.id}" }
            }

    fun noInternalPlatform(metadata: PublishedMetadata): List<String> =
        if (INTERNAL_PLATFORM in metadata.text) {
            listOf(
                "${metadata.artifact}: names $INTERNAL_PLATFORM"
            )
        } else {
            emptyList()
        }

    fun everyDependencyHasAVersion(metadata: PublishedMetadata): List<String> =
        metadata.pomDependencies.filter { it.version.isNullOrBlank() }
            .map { "${metadata.artifact}: the POM gives ${it.id} no version" } +
            metadata.variants.flatMap { (variant, dependencies) ->
                dependencies.filter { it.version.isNullOrBlank() }
                    .map { "${metadata.artifact}: variant $variant gives ${it.id} no version" }
            }

    fun noTools(metadata: PublishedMetadata): List<String> {
        val named = metadata.pomDependencies.map { it.id } + metadata.pomManaged.map { it.id } +
            metadata.variants.values.flatten().map { it.id } + metadata.constraints.values.flatten().map { it.id }
        return named.distinct().filter { id -> TOOLS.any { id.startsWith(it) } }
            .map { "${metadata.artifact}: names the test or build tool $it" }
    }

    /**
     * What Maven brings at run time (compile and runtime scope, not optional) is in the Gradle run-time variant; what
     * only the API variant has must be `provided` (or optional) in the POM.
     */
    fun pomBringsNoMoreThanRuntimeElements(metadata: PublishedMetadata): List<String> {
        val runtime = metadata.variants["runtimeElements"].orEmpty().map { platformJar(it.id) }.toSet()
        return metadata.pomDependencies.filter { it.atRuntime && platformJar(it.id) !in runtime }
            .map { "${metadata.artifact}: the POM brings ${it.id} at run time, which runtimeElements does not" }
    }

    /**
     * A Kotlin multiplatform library is `kotlinx-coroutines-core` to Gradle, which redirects to its JVM variant, and
     * `kotlinx-coroutines-core-jvm` in a POM that `versionMapping` wrote from the resolution: the same jar.
     */
    private fun platformJar(id: String): String = id.removeSuffix("-jvm")

    /** The BOM lists these CoSec modules, as `cosec-…` artifact ids, and nothing else. */
    fun bomListsExactly(metadata: PublishedMetadata, expected: Set<String>): List<String> {
        val listed = (
            metadata.pomManaged.map { it.group to it.name } +
                metadata.constraints.values.flatten().map { it.group to it.name }
            ).toSet()
        val foreign = listed.filter { it.first != "me.ahoo.cosec" }.map { "${it.first}:${it.second}" }
        val names = listed.filter { it.first == "me.ahoo.cosec" }.map { it.second }.toSet()
        return foreign.map { "${metadata.artifact}: lists $it, which is not a CoSec module" } +
            (expected - names).map { "${metadata.artifact}: does not list $it" } +
            (names - expected).map { "${metadata.artifact}: lists $it, which is not a dependency" } +
            metadata.variants.values.flatten().map { "${metadata.artifact}: depends on ${it.id}, a BOM only constrains" }
    }

    /**
     * What Maven brings of CoSec at run time from [artifact]: the CoSec modules reachable through compile and runtime
     * scope, not optional. [published] maps an artifact id to its metadata.
     */
    fun runtimeClosure(artifact: String, published: Map<String, PublishedMetadata>): Set<String> {
        val seen = linkedSetOf<String>()
        val queue = ArrayDeque(listOf(artifact))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            val metadata = published[next] ?: continue
            metadata.pomDependencies.filter { it.atRuntime && it.group == "me.ahoo.cosec" }
                .map { it.name }.filter { seen.add(it) }.forEach(queue::addLast)
        }
        return seen
    }
}
