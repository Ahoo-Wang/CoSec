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
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * The kit decides every `engine` scenario of the conformance suite as the suite specifies, compared exactly: what
 * users run is what the specification checks.
 */
class ConformanceScenariosTest {
    private val scenarios = Path.of("..", "conformance", "src", "test", "resources", "scenarios")
    private val engine = EngineScenarios()

    @TestFactory
    fun `engine scenarios of the conformance suite`(): List<DynamicTest> {
        val files = Files.walk(scenarios).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "json" }.toList().sortedBy { it.toString() }
        }
        return files.map { ScenarioJson.read(it) to it.relativeTo(scenarios).invariantSeparatorsPathString }
            .filter { (node, _) -> node.required("level").text() == "engine" }
            .also { check(it.isNotEmpty()) { "No engine scenario under ${scenarios.toAbsolutePath()}" } }
            .map { (node, name) ->
                DynamicTest.dynamicTest(name) {
                    val outcome = engine.run(node, HEADER)
                    EngineExpectations.check(name, node.required("expect"), outcome, exact = true)
                }
            }
    }

    private companion object {
        val HEADER = setOf("\$comment", "rules", "polarity", "level", "description")
    }
}
