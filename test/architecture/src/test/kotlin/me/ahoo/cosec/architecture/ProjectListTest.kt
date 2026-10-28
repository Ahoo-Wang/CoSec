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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText

/**
 * RFC 0011 §3 and G6: every project sits in exactly one group, at `<group>/<name>`, the files that list projects by
 * hand (the PR labeler, the bug report template) list each one, and CI, which derives its jobs from the groups
 * (`scripts/ci/plan.py`), tests each one.
 */
class ProjectListTest {
    companion object {
        /** The project list (RFC 0011 §3). `settings.gradle.kts` includes exactly these. */
        val GROUPS: Map<String, List<String>> = linkedMapOf(
            "api" to listOf("api"),
            "core" to listOf("model", "engine", "runtime", "control", "core"),
            "backends" to listOf("cocache", "kafka"),
            "edge" to listOf("webflux", "webmvc", "gateway", "openapi"),
            "extensions" to listOf("jwt", "social", "ip2region", "micrometer", "opentelemetry"),
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

        /** The projects whose tests write a JaCoCo report: the root build's library projects. */
        private val COVERED: Set<String> = requireNotNull(System.getProperty("cosec.coverage.projects")) {
            "cosec.coverage.projects is not set: run the rules through Gradle."
        }.split(';').filter { it.isNotBlank() }.toSet()

        /** The starter keeps its 7.x artifact id until RFC 0011 PR-2 (D2). */
        fun artifactOf(project: String): String =
            if (project == "spring-boot") "cosec-spring-boot-starter" else "cosec-$project"
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
    fun `CI checks every tested project once, in a job of its own group`() {
        val jobs = ciPlan()["jobs"].items().map { it["group"].asString() to it["projects"].asString().split(' ') }
        jobs.flatMap { it.second }.assert().containsExactlyInAnyOrderElementsOf(PROJECTS.filterNot { it in UNTESTED })
        for ((group, projects) in jobs) {
            GROUPS.getValue(group).assert().containsAll(projects)
        }
    }

    @Test
    fun `CI leaves out only the untested projects, and expects a coverage report from every library project`() {
        val plan = ciPlan()
        plan["excluded"].strings().assert().containsExactlyInAnyOrderElementsOf(UNTESTED)
        plan["uncovered"].strings().assert()
            .containsExactlyInAnyOrderElementsOf(PROJECTS.filterNot { it in UNTESTED || it in COVERED })
    }

    /** The plan CI runs, from the script itself, which also fails when the groups and `codecov.yml` disagree. */
    private fun ciPlan(): JsonNode {
        val process = ProcessBuilder("python3", "-I", "scripts/ci/plan.py", "--root", Projects.root.toString())
            .directory(Projects.root.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(1, TimeUnit.MINUTES).assert().isTrue()
        process.exitValue().assert().describedAs(output).isZero()
        return JsonMapper.builder().build().readTree(output)
    }

    private fun JsonNode.items(): List<JsonNode> = (0 until size()).map { get(it) }

    private fun JsonNode.strings(): List<String> = items().map { it.asString() }
}
