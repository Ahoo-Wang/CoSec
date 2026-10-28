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
import kotlin.io.path.readText

/**
 * RFC 0011 §3 and G6: every project sits in exactly one group, at `<group>/<name>`, and the files that list projects
 * by hand (the PR labeler, the bug report template, the integration test workflow) list each one.
 */
class ProjectListTest {
    companion object {
        /** The project list (RFC 0011 §3). `settings.gradle.kts` includes exactly these. */
        val GROUPS: Map<String, List<String>> = linkedMapOf(
            "api" to listOf("annotations", "api"),
            "core" to listOf("model", "engine", "spi", "runtime", "control"),
            "backends" to listOf("cocache", "kafka"),
            "edge" to listOf("webflux", "webmvc", "gateway", "openapi"),
            "extensions" to listOf("jwt", "social", "login", "ip2region", "micrometer", "opentelemetry"),
            "host" to listOf("spring-boot"),
            "migration" to listOf("migrate"),
            "test" to listOf("testkit", "conformance", "architecture", "e2e", "code-coverage-report"),
            "bom" to listOf("bom", "dependencies"),
            "platform" to listOf("gateway-server"),
        )

        val PROJECTS: List<String> = GROUPS.values.flatten()

        /** No label: the coverage report has no sources of its own. */
        private val UNLABELED = setOf("code-coverage-report")

        /** Not something a user reports a bug against: test-only projects and the BOMs. */
        private val UNREPORTED =
            setOf("conformance", "architecture", "e2e", "code-coverage-report", "bom", "dependencies")

        /** No tests to run: the BOMs, the coverage report, and the gateway server, which `docker-deploy` builds. */
        private val UNTESTED = setOf("bom", "dependencies", "code-coverage-report", "gateway-server")

        fun artifactOf(project: String): String = "cosec-$project"
    }

    @Test
    fun `every project is in exactly one group`() {
        PROJECTS.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.assert().isEmpty()
    }

    @Test
    fun `the build includes exactly the listed projects, each at its group directory`() {
        Projects.directories.keys.assert().containsExactlyInAnyOrderElementsOf(PROJECTS)
        for ((group, names) in GROUPS) {
            for (name in names) {
                Projects.directory(name).assert().isEqualTo(Projects.root.resolve(group).resolve(name))
            }
        }
    }

    @Test
    fun `the labeler labels every project directory`() {
        val labeler = Projects.root.resolve(".github/labeler.yml").readText()
        val globs = Regex("""'([a-z-]+/[a-z0-9-]+)/\*\*'""").findAll(labeler).map { it.groupValues[1] }.toSet()
        val expected = GROUPS.flatMap { (group, names) -> names.filterNot { it in UNLABELED }.map { "$group/$it" } }
        globs.assert().containsExactlyInAnyOrderElementsOf(expected)
    }

    @Test
    fun `the bug report template offers every published project`() {
        val template = Projects.root.resolve(".github/ISSUE_TEMPLATE/bug_report.yml").readText()
        val block = Regex("""id: modules\n(.*?)\n\s*validations:""", RegexOption.DOT_MATCHES_ALL).find(template)
        block.assert().isNotNull()
        val options = Regex("""(?m)^\s+- (.+)$""").findAll(block!!.groupValues[1]).map { it.groupValues[1].trim() }
        val expected = PROJECTS.filterNot { it in UNREPORTED }.map(::artifactOf) + "Not sure"
        options.toList().assert().containsExactlyInAnyOrderElementsOf(expected)
    }

    @Test
    fun `the integration test workflow checks every tested project`() {
        val workflow = Projects.root.resolve(".github/workflows/integration-test.yml").readText()
        val checked = Regex("""([a-z0-9-]+):check\b""").findAll(workflow).map { it.groupValues[1] }.toSet()
        checked.assert().containsExactlyInAnyOrderElementsOf(PROJECTS.filterNot { it in UNTESTED })
    }
}
