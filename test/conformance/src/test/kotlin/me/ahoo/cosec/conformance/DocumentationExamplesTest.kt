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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.conformance

import com.example.iam.Onboarding
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.DefaultControlPlane
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.local.LocalControlService
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.route.RouteMapCodec
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.testkit.PolicyTestKit
import me.ahoo.cosec.testkit.ScenarioJson
import me.ahoo.cosec.testkit.strings
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * The samples of the scenario pages (`wiki/examples/<scenario>/`) are real: the documents compile, the scenarios decide
 * what the page says, the change requests decode, and the route maps load and name only actions the app documents
 * define. A page includes these files, so a sample cannot drift from what CoSec accepts.
 *
 * Layout of an example: `documents/` (as `cosec-migrate convert` writes it) with `scenarios/` (test kit scenarios);
 * `documents-<variant>/` with `scenarios-<variant>/`; `changes/` (bodies of `POST /changes`, each operation decoded as the control API decodes it); `routes/` (route maps).
 */
class DocumentationExamplesTest {
    private val examples: Path =
        Path.of(checkNotNull(System.getProperty("cosec.repository.root")), "wiki", "examples")

    private fun exampleDirectories(): List<Path> = examples.listDirectoryEntries().filter {
        it.isDirectory()
    }.sortedBy { it.name }

    @Test
    fun `the examples exist`() {
        exampleDirectories().assert().isNotEmpty()
    }

    @TestFactory
    fun `documents compile and scenarios decide what the page says`(): List<DynamicTest> =
        exampleDirectories().flatMap { example ->
            example.listDirectoryEntries("documents*").sortedBy { it.name }.flatMap { documents ->
                val variant = documents.name.removePrefix("documents")
                val scenarios = example.resolve("scenarios$variant")
                val name = "${example.name}/${documents.name}"
                val kit by lazy { PolicyTestKit.load(documents) }
                listOf(DynamicTest.dynamicTest("$name compile") { kit }) +
                    kit(scenarios, name) { kit }
            }
        }

    private fun kit(scenarios: Path, name: String, kit: () -> PolicyTestKit): List<DynamicTest> {
        scenarios.isDirectory().assert().describedAs("$name has scenarios").isTrue()
        val files = Files.walk(scenarios).use { paths ->
            paths.filter { it.extension == "json" }.toList().sortedBy { it.toString() }
        }
        files.assert().describedAs("scenarios of $name").isNotEmpty()
        return files.map { file ->
            val label = "$name: ${scenarios.relativize(file)}"
            DynamicTest.dynamicTest(label) { kit().check(label, file.readText()) }
        }
    }

    @TestFactory
    fun `change requests decode`(): List<DynamicTest> = exampleDirectories().flatMap { example ->
        jsonFiles(example.resolve("changes")).map { file ->
            DynamicTest.dynamicTest("${example.name}/changes/${file.name}") {
                val body = ScenarioJson.read(file)
                (body.properties().map { it.key } - setOf("document", "base", "note", "operations", "confirmations"))
                    .assert().isEmpty()
                val key = ChangeRequestCodec.documentKeyOf(body.get("document").asString())
                body.get("operations").values().map { ChangeRequestCodec.operation(key, it) }.assert().isNotEmpty()
            }
        }
    }

    @TestFactory
    fun `route maps load and name only actions an app document defines`(): List<DynamicTest> =
        exampleDirectories().flatMap { example ->
            val defined = example.listDirectoryEntries("documents*").flatMap { jsonFiles(it.resolve("apps")) }
                .flatMap { actionsOfCatalog(ScenarioJson.read(it)) }.toSet()
            jsonFiles(example.resolve("routes")).map { file ->
                DynamicTest.dynamicTest("${example.name}/routes/${file.name}") {
                    RouteMapCodec.load(listOf(file.name to file.readBytes()))
                    val routed = ScenarioJson.read(
                        file
                    ).get("routes").values().map { it.get("action").asString() }.toSet()
                    routed.filterNot { it.startsWith("http:") || it in defined }.assert().isEmpty()
                }
            }
        }

    /**
     * `CTL-65`: the sample of the control plane's facade (`control-sdk/kotlin`, compiled with these tests and opting in
     * to no internal API) proposes its change for the user whose request it serves, reads the answer and reads the
     * document, against a control plane wired as a host wires it.
     */
    @Test
    fun `the control plane's facade sample proposes a tenant change and reads the answer and the document`() =
        runBlocking<Unit> {
            val example = examples.resolve("control-sdk")
            val clock = MutableClock(Instant.parse("2026-11-21T00:00:00Z"))
            val store = InMemoryControlStore(clock)
            val revisions = Revisions(store, clock)
            ChangeRunner.bootstrap(revisions, ScenarioJson.read(example.resolve("bootstrap.json")))
            val engine = ControlEngine(revisions)
            val changes = Changes(
                store,
                revisions,
                { ChangeId("c1") },
                engine.authorizer,
                clock,
                ChangeOptions(),
                revocations = RevocationReader.NONE,
                lockout = LockoutCheck.UNCHECKED,
            )
            val plane = DefaultControlPlane(
                changes,
                revisions,
                ChangeAuthority(engine.authorizer, revocations = RevocationReader.NONE)
            )
            val service = LocalControlService(plane, IdentityGate(clock = clock))
            val alice = engine.security(TenantId("acme"), "alice", roles = listOf("admin"))
            val proposed = Onboarding(service).propose(
                alice.securityContext,
                "acme",
                example.resolve("changes/auditors.json").readText(),
            )
            proposed.change.assert().isEqualTo("c1")
            proposed.state.assert().isEqualTo("CHECKED")
            proposed.revision.assert().isEqualTo(1L)
            // Recorded as alice's own proposal.
            changes.get(ChangeId("c1")).author.principal.value.assert().isEqualTo("alice")
        }

    private fun jsonFiles(directory: Path): List<Path> =
        if (directory.isDirectory()) directory.listDirectoryEntries("*.json").sortedBy { it.name } else emptyList()

    /** The actions of every short-form permission of a catalog (`action` is a pattern or an array of them). */
    private fun actionsOfCatalog(document: JsonNode): List<String> =
        document.get("catalog")?.get("groups")?.values().orEmpty().flatMap { group ->
            group.get("permissions").values().mapNotNull { it.get("action") }.flatMap { it.strings() }
        }
}
