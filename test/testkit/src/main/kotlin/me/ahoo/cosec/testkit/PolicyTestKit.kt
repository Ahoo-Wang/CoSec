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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.testkit

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
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
 * Requests name their action and resource; the test kit does not evaluate HTTP requests through a route map. It reads
 * them as enforcement reads a request (`PIP-15`, `PIP-18`): `http:HEAD` is decided as `http:GET`, an `http` path is
 * normalized, and what the pipeline answers `400` (a lower-case method, a path with a dot segment, a typed id beyond
 * a request path's bounds) is [EngineOutcome.InvalidRequest], `{ "decision": "InvalidRequest", "refused": "action" }`. An `expect` must name a `decision`.
 */
@ExperimentalCoSecApi
class PolicyTestKit private constructor(private val snapshot: CompiledSnapshot) {
    private val scenarios = EngineScenarios(DocumentSnapshotCompiler.stored, enforceable = true)

    /** What the documents decide for [scenario], a scenario as JSON text; its `expect`, if any, is not checked. */
    fun evaluate(scenario: String): EngineOutcome = evaluate(ScenarioJson.parse(scenario))

    private fun evaluate(scenario: JsonNode): EngineOutcome = scenarios.run(scenario, HEADER, snapshot)

    /**
     * Throws [AssertionError] naming the scenario [name] when the documents do not decide what the `expect` of
     * [scenario], a scenario as JSON text, says.
     */
    fun check(name: String, scenario: String) = check(name, ScenarioJson.parse(scenario))

    /** Checks the scenario in [file]. */
    fun check(file: Path) = check(file.toString(), ScenarioJson.read(file))

    private fun check(name: String, scenario: JsonNode) {
        val expected = scenario.required("expect")
        EngineExpectations.check(name, expected, evaluate(scenario), exact = false)
    }

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

        /** What `cosec-migrate convert` writes beside the documents: the ids of the files it had to shorten. */
        private const val REPORT_FILE = "report.json"

        /** What `cosec-migrate convert` puts in a shortened file name, before the SHA-256 of the id. */
        private const val BOUNDED_SEPARATOR = '~'
        const val APPS_DIRECTORY = "apps"
        const val TENANTS_DIRECTORY = "tenants"
        private const val JSON = "json"
        private val HEADER = setOf("\$comment", "description")

        /**
         * The documents in [directory]. Without a `platform.json` the platform document is empty; no other file of the
         * directory is read. Throws [DocumentException] when a document does not compile.
         */
        fun load(directory: Path): PolicyTestKit =
            PolicyTestKit(DocumentSnapshotCompiler.stored.compile(documents(directory)))

        /** The documents under [directory]: see [load]. */
        internal fun documents(directory: Path): ScenarioDocuments {
            require(directory.isDirectory()) { "No document directory at ${directory.toAbsolutePath()}" }
            val platform = directory.resolve(PLATFORM_FILE).takeIf { it.exists() }?.let(ScenarioJson::read)
            val ids = recordedIds(directory)
            return ScenarioDocuments(
                platform,
                documents(directory, APPS_DIRECTORY, ids),
                documents(directory, TENANTS_DIRECTORY, ids),
            )
        }

        /**
         * The ids `report.json` records for files whose names `convert` shortened (an id longer than a file name may be:
         * cut, `~`, the SHA-256 of the id), by their paths relative to the directory; none without the report.
         */
        private fun recordedIds(directory: Path): Map<String, String> {
            val report = directory.resolve(REPORT_FILE).takeIf { it.isRegularFile() } ?: return emptyMap()
            return ScenarioJson.read(report).get("documentIds")?.fields().orEmpty().mapValues { it.value.text() }
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

        /**
         * The documents in [subdirectory] of [directory] by id: the URL-decoded file name, or the id [ids] records for a
         * shortened one (round 2 L1). A shortened name without a recorded id fails: decoded, it would name another id,
         * and the scenarios of the real one would read an empty document.
         */
        private fun documents(directory: Path, subdirectory: String, ids: Map<String, String>): Map<String, JsonNode> {
            val path = directory.resolve(subdirectory)
            if (!path.exists()) return emptyMap()
            return path.listDirectoryEntries("*.$JSON").sortedBy { it.name }.associate { file ->
                val relative = "$subdirectory/${file.name}"
                val name = file.name.removeSuffix(".$JSON")
                val id = ids[relative] ?: if (BOUNDED_SEPARATOR in name) {
                    throw ScenarioFormatException(
                        "$relative holds `~`, which a file name of an id never does: either cosec-migrate convert " +
                            "shortened it, and its id is in the $REPORT_FILE convert wrote beside the documents (keep " +
                            "it there), or the id holds a `~`, which its file name writes URL-encoded, as %7E",
                    )
                } else {
                    URLDecoder.decode(name, Charsets.UTF_8)
                }
                id to ScenarioJson.parse(file.readText())
            }
        }
    }
}
