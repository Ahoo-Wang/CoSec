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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/**
 * RFC 0011 §5, the dependency graph: what each project declares in its main configurations, and what the inner ones
 * resolve. Replaces the root build's verifyPureClasspath.
 */
class DependencyRulesTest {
    private companion object {
        const val COSID = "me.ahoo.cosid:"

        /** How a build script names CosId: by coordinates, or through the catalog's `cosid-*` entries. */
        val COSID_REFERENCES = listOf("me.ahoo.cosid", "libs.cosid", "libs.versions.cosid")
    }

    private val libraryConfigurations = setOf("api", "implementation", "compileOnly", "runtimeOnly")

    private fun declaredProjects(project: String): List<String> = Projects.facts[project].orEmpty()
        .map { it.split(' ') }
        .filter { it[0] == "declared" && it[1] in libraryConfigurations && it[2] == "project" }
        .map { it[3] }
        .filter { it !in DependencyRules.PLATFORMS }

    private fun modules(project: String, kind: String, configuration: String? = null): List<String> =
        Projects.facts[project].orEmpty()
            .map { it.split(' ') }
            .filter { it[0] == kind && it[2] == "module" && (configuration == null || it[1] == configuration) }
            .map { it[3] }

    private fun resolvedProjects(project: String): List<String> = Projects.facts[project].orEmpty()
        .map { it.split(' ') }
        .filter { it[0] == "resolved" && it[2] == "project" }
        .map { it[3] }

    @Test
    fun `every project with sources has its facts`() {
        Projects.facts.filterValues { it.isEmpty() }.keys
            .filter { Projects.groupOf(it) != "bom" && it != "code-coverage-report" }
            .assert().isEmpty()
    }

    @Test
    fun `main code never depends on a test or platform project`() {
        val violations = ProjectListTest.PROJECTS.flatMap { project ->
            declaredProjects(project)
                .filter { Projects.groupOf(it) in DependencyRules.NEVER_DEPENDED_ON }
                .map { "$project -> $it" }
        }
        violations.assert().isEmpty()
    }

    @Test
    fun `dependencies point inwards, outer modules do not depend on each other`() {
        val violations = DependencyRules.ALLOWED.flatMap { (project, allowed) ->
            declaredProjects(project)
                .filterNot { it in allowed }
                .filterNot { to -> DependencyRules.EXCEPTIONS.any { it.from == project && it.to == to } }
                .map { "$project -> $it" }
        }
        violations.assert().isEmpty()
    }

    @Test
    fun `every exception is still needed`() {
        DependencyRules.EXCEPTIONS.filterNot { it.to in declaredProjects(it.from) }
            .map { "${it.from} -> ${it.to}" }
            .assert().isEmpty()
    }

    @Test
    fun `the contracts and the engine resolve the Kotlin standard library only`() {
        val pure = setOf("annotations", "api", "model", "engine")
        val violations = pure.flatMap { project ->
            modules(project, "resolved").filterNot { it in DependencyRules.PURE_LIBRARIES }.map { "$project: $it" } +
                resolvedProjects(project).filterNot { it in pure }.map { "$project: project $it" }
        }
        violations.assert().isEmpty()
    }

    @Test
    fun `the ports resolve the model, the contracts and coroutines only`() {
        val inner = setOf("annotations", "api", "model", "spi")
        val violations = modules("spi", "resolved").filterNot { it in DependencyRules.SPI_LIBRARIES } +
            resolvedProjects("spi").filterNot { it in inner }.map { "project $it" }
        violations.assert().isEmpty()
    }

    @Test
    fun `runtime and control never have Reactor, Spring, the Servlet API or a Redis client`() {
        val violations = listOf("runtime", "control").flatMap { project ->
            modules(project, "resolved", "runtimeClasspath")
                .filter { module -> DependencyRules.NEVER_IN_CORE.any { module.startsWith(it) } }
                .map { "$project: $it" }
        }
        violations.assert().isEmpty()
    }

    /**
     * Only [DependencyRules.COSID_OWNERS] declare or resolve CosId, in their main configurations and their build
     * scripts, and nothing brings it at run time but `cosec-cosid`. The platforms have no facts, so the build scripts
     * are read too: no BOM imports CosId or constrains its version.
     */
    @Test
    fun `only cosec-cosid and the starter declare or resolve CosId, and no BOM imports it`() {
        fun cosId(project: String, configuration: String? = null) =
            (modules(project, "declared", configuration) + modules(project, "resolved", configuration))
                .filter { it.startsWith(COSID) }
                .distinct()
        DependencyRules.COSID_OWNERS.keys.filter { cosId(it).isEmpty() }
            .assert().describedAs("owners whose facts name no CosId: the rule would check nothing").isEmpty()
        val violations = Projects.facts.keys.filterNot { it in DependencyRules.COSID_OWNERS }.flatMap { project ->
            cosId(project).map { "$project: $it" }
        } + cosId("spring-boot", "runtimeClasspath").map { "spring-boot runtimeClasspath: $it" }
        violations.assert().isEmpty()
        val others = Projects.directories.filterKeys { it !in DependencyRules.COSID_OWNERS }.values
        val scripts = (others + listOf(Projects.root))
            .map { it.resolve("build.gradle.kts") }
            .filter { it.exists() }
            .filter { script -> COSID_REFERENCES.any { it in script.readText() } }
            .map { it.relativeTo(Projects.root).toString() }
        scripts.assert().isEmpty()
    }

    @Test
    fun `the backends declare no Reactor`() {
        val violations = listOf("cocache", "kafka").flatMap { project ->
            modules(project, "declared").filter { it.startsWith("io.projectreactor:") }.map { "$project: $it" }
        }
        violations.assert().isEmpty()
    }
}
