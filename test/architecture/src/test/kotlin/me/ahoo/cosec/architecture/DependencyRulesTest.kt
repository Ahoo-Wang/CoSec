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

/**
 * RFC 0011 §5, the dependency graph: what each project declares in its main configurations, and what the inner ones
 * resolve. Replaces the root build's verifyPureClasspath.
 */
class DependencyRulesTest {
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

    @Test
    fun `the backends declare no Reactor`() {
        val violations = listOf("cocache", "kafka").flatMap { project ->
            modules(project, "declared").filter { it.startsWith("io.projectreactor:") }.map { "$project: $it" }
        }
        violations.assert().isEmpty()
    }
}
