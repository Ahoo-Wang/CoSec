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

package me.ahoo.cosec.testkit

import org.junit.jupiter.api.DynamicTest
import tools.jackson.databind.JsonNode
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/**
 * Tests an application's own policy documents (RFC 0008 Q11). Each scenario is a request and the decision expected for
 * it, written in the `engine` format of the conformance suite, and evaluated as the suite evaluates it: the same
 * compiler, membership and scope resolution, and engine ([EngineScenarios]).
 *
 * The documents are a directory laid out as `cosec-migrate convert` writes it: `platform.json`, `apps/<app>.json` and
 * `tenants/<tenant>.json`, each a document as published (ids in file names URL-encoded). A document that does not
 * compile fails [load].
 *
 * A scenario has the fields of an engine scenario of the suite without `documents`: `description`, `credential`,
 * `membershipProvider`, `request` and `expect`. Unlike the suite, only the fields `expect` names are compared, so
 * `{ "decision": "Permit" }` passes whichever statement permits, and `"by"` pins the statement:
 *
 * ```json
 * {
 *   "description": "A member of acme reads an order",
 *   "credential": { "tenant": "acme", "id": "alice", "roles": ["reader"] },
 *   "request": { "action": "order:read", "resource": { "type": "order", "id": "1" } },
 *   "expect": { "decision": "Permit" }
 * }
 * ```
 *
 * ```kotlin
 * class PolicyTest {
 *     private val kit = PolicyTestKit.load(Path.of("src/main/resources/cosec"))
 *
 *     @TestFactory
 *     fun policies(): List<DynamicTest> = kit.tests(Path.of("src/test/resources/policy-scenarios"))
 * }
 * ```
 *
 * Requests name their action and resource; the test kit does not evaluate HTTP requests through a route map.
 */
class PolicyTestKit private constructor(private val snapshot: CompiledSnapshot) {
    private val scenarios = EngineScenarios(DocumentSnapshotCompiler.stored)

    /** What the documents decide for [scenario], a scenario as JSON text; its `expect`, if any, is not checked. */
    fun evaluate(scenario: String): EngineOutcome = evaluate(ScenarioJson.parse(scenario))

    fun evaluate(scenario: JsonNode): EngineOutcome = scenarios.run(scenario, HEADER, snapshot)

    /** Throws [AssertionError] naming the scenario [name] when the documents do not decide what its `expect` says. */
    fun check(name: String, scenario: JsonNode) {
        val expected = scenario.required("expect")
        EngineExpectations.check(name, expected, evaluate(scenario), exact = false)
    }

    /** Checks the scenario in [file]. */
    fun check(file: Path) = check(file.toString(), ScenarioJson.read(file))

    /**
     * A dynamic test for each `.json` file under [directory], named by its path relative to it, for a JUnit 5
     * `@TestFactory`. An empty directory is an error, so a mistyped path fails rather than passes.
     */
    fun tests(directory: Path): List<DynamicTest> {
        require(directory.isDirectory()) { "No scenario directory at ${directory.toAbsolutePath()}" }
        val files = Files.walk(directory).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == JSON }.toList().sortedBy { it.toString() }
        }
        require(files.isNotEmpty()) { "No scenario (*.json) under ${directory.toAbsolutePath()}" }
        return files.map { file ->
            val name = file.relativeTo(directory).invariantSeparatorsPathString
            DynamicTest.dynamicTest(name, file.toUri()) { check(name, ScenarioJson.read(file)) }
        }
    }

    companion object {
        const val PLATFORM_FILE = "platform.json"
        const val APPS_DIRECTORY = "apps"
        const val TENANTS_DIRECTORY = "tenants"
        private const val JSON = "json"
        private val HEADER = setOf("\$comment", "description")

        /**
         * The documents in [directory]. Without a `platform.json` the platform document is empty; no other file of the
         * directory is read. Throws [DocumentException] when a document does not compile.
         */
        fun load(directory: Path): PolicyTestKit {
            require(directory.isDirectory()) { "No document directory at ${directory.toAbsolutePath()}" }
            val platform = directory.resolve(PLATFORM_FILE).takeIf { it.exists() }?.let(ScenarioJson::read)
            return of(
                platform,
                documents(directory.resolve(APPS_DIRECTORY)),
                documents(directory.resolve(TENANTS_DIRECTORY))
            )
        }

        /** The documents as JSON text: [apps] and [tenants] by id. Throws [DocumentException] when one does not compile. */
        fun of(platform: String?, apps: Map<String, String> = emptyMap(), tenants: Map<String, String> = emptyMap()) =
            of(
                platform?.let(ScenarioJson::parse),
                apps.mapValues { ScenarioJson.parse(it.value) },
                tenants.mapValues { ScenarioJson.parse(it.value) },
            )

        private fun of(platform: JsonNode?, apps: Map<String, JsonNode>, tenants: Map<String, JsonNode>): PolicyTestKit =
            PolicyTestKit(DocumentSnapshotCompiler.stored.compile(ScenarioDocuments(platform, apps, tenants)))

        private fun documents(directory: Path): Map<String, JsonNode> {
            if (!directory.exists()) return emptyMap()
            return directory.listDirectoryEntries("*.$JSON").sortedBy { it.name }.associate { file ->
                URLDecoder.decode(file.name.removeSuffix(".$JSON"), Charsets.UTF_8) to ScenarioJson.parse(file.readText())
            }
        }
    }
}
