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

package me.ahoo.cosec.testkit

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `PIP-12` (#1547): header names that differ only in case are one header, as in the pipeline. The kit keeps every value
 * under the HTTP name and answers a header the documents read, sent with different values, as the pipeline does: 400,
 * never the last value of the scenario's JSON object deciding.
 */
class KitHeadersTest {
    private fun platform(attribute: String) = """{
        "policies":[{"id":"read","statements":[{"effect":"allow","action":"http:GET",
          "condition":{"eq":["$attribute","yes"]}}]}],
        "bindings":[{"id":"read","subject":"*","app":"*","space":"*","policy":"read"}]}"""

    private val xMode = platform("http.header.x-mode")
    private val alice = PipelineFixture.Principal("alice", tenant = "acme")

    private fun scenario(headers: String) =
        """{"credential":{"tenant":"acme","id":"alice"},
            "request":{"action":"http:GET","resource":{"type":"http","id":"/orders/1"},"headers":$headers}}"""

    /** The kit's outcome and the pipeline's status for the same documents and headers, in the same order. */
    private fun both(platform: String, vararg headers: Pair<String, String>): Pair<EngineOutcome, Int> {
        val json = headers.joinToString(",", "{", "}") { (name, value) -> "\"$name\":\"$value\"" }
        val outcome = PolicyTestKit.of(platform).evaluate(scenario(json))
        val status = PipelineFixture.of(platform = platform).use { fixture ->
            runBlocking {
                fixture.evaluate(fixture.request("GET", "/orders/1", alice, headers = linkedMapOf(*headers))).status
            }
        }
        return outcome to status
    }

    private fun EngineOutcome.assertRefusedHeader() =
        describe().assert().isEqualTo("""{"decision":"InvalidRequest","refused":"header"}""")

    @Test
    fun `a header sent in two cases with different values is refused, whichever comes last`() {
        listOf(
            arrayOf("X-Mode" to "no", "x-mode" to "yes"),
            arrayOf("x-mode" to "yes", "X-Mode" to "no"),
        ).forEach { headers ->
            val (outcome, status) = both(xMode, *headers)
            status.assert().isEqualTo(400)
            outcome.assertRefusedHeader()
        }
    }

    @Test
    fun `identical repeats are one value, by the kit as by the pipeline`() {
        val (outcome, status) = both(xMode, "X-Mode" to "yes", "x-mode" to "yes")
        status.assert().isEqualTo(200)
        (outcome as EngineOutcome.Decided).decision.assert().isInstanceOf(Decision.Permit::class.java)
    }

    @Test
    fun `a header no document reads is not checked`() {
        val (outcome, status) = both(xMode, "X-Other" to "a", "x-other" to "b", "x-mode" to "yes")
        status.assert().isEqualTo(200)
        (outcome as EngineOutcome.Decided).decision.assert().isInstanceOf(Decision.Permit::class.java)
    }

    @Test
    fun `Origin and Referer are checked the same way`() {
        val origin = platform("http.origin")
        val (outcome, status) = both(origin, "Origin" to "a", "origin" to "b")
        status.assert().isEqualTo(400)
        outcome.assertRefusedHeader()
        val referer = platform("http.referer.host")
        val (second, secondStatus) = both(referer, "Referer" to "https://a.test/", "referer" to "https://b.test/")
        secondStatus.assert().isEqualTo(400)
        second.assertRefusedHeader()
    }

    @Test
    fun `a scenario expecting the refusal passes, and one expecting a permit does not`() {
        val kit = PolicyTestKit.of(xMode)
        kit.check(
            "ambiguous",
            """{"credential":{"tenant":"acme"},"request":{"action":"http:GET","resource":{"type":"http","id":"/orders/1"},
               "headers":{"X-Mode":"no","x-mode":"yes"}},"expect":{"decision":"InvalidRequest","refused":"header"}}""",
        )
        val failure = runCatching {
            kit.check(
                "ambiguous",
                """{"credential":{"tenant":"acme"},"request":{"action":"http:GET","resource":{"type":"http","id":"/orders/1"},
                   "headers":{"X-Mode":"no","x-mode":"yes"}},"expect":{"decision":"Permit"}}""",
            )
        }.exceptionOrNull()
        failure.assert().isInstanceOf(AssertionError::class.java)
    }

    /** The engine level of the format, which states what the engine decides (no enforcement): no last value wins. */
    @Test
    fun `an engine-level scenario never lets the last value decide`() {
        val outcome = EngineScenarios().run(
            ScenarioJson.parse(
                """{"documents":{"platform":$xMode},"credential":{"tenant":"acme"},
                   "request":{"action":"http:GET","resource":{"type":"http","id":"/orders/1"},
                   "headers":{"X-Mode":"no","x-mode":"yes"}}}"""
            ),
            emptySet(),
        )
        (outcome as EngineOutcome.Decided).decision.assert().isInstanceOf(Decision.Deny::class.java)
    }
}
