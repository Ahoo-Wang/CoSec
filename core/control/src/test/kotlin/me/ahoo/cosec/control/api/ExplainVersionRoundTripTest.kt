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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/** The `version` an `explain` answers with is what `at` takes (#1391): a document it did not read has no member. */
class ExplainVersionRoundTripTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin", "ops@team-a"))

    private fun explain(body: String): Pair<Int, JsonNode> = runBlocking {
        val response = http.handle(ControlHttpRequest("POST", "/explain", emptyMap(), body.encodeToByteArray()), alice)
        response.status to CoSecJson.mapper.readTree(response.body)
    }

    private val unmapped = """{"tenant":"acme","principal":"bob","action":"http:GET","resource":"/orders",""" +
        """"time":"2026-11-01T00:00:00Z"}"""
    private val withApp = """{"tenant":"acme","principal":"bob","roles":["ops@team-a"],"app":"order",""" +
        """"space":"team-a","action":"order:read","resource":"order/1","time":"2026-11-01T00:00:00Z"}"""

    @Test
    fun `a version without an app document has no app member, and is accepted as at`() {
        val (status, first) = explain("""{"request":$unmapped}""")
        status.assert().isEqualTo(200)
        val version = first.get("version")
        version.has("platform").assert().isTrue()
        version.has("app").assert().isFalse()

        val (replayed, again) = explain("""{"request":$unmapped,"at":$version}""")

        replayed.assert().describedAs("$again").isEqualTo(200)
        again.get("version").assert().isEqualTo(version)
        again.get("decision").get("decision").assert().isEqualTo(first.get("decision").get("decision"))
    }

    @Test
    fun `a version with every member round-trips too`() {
        val first = explain("""{"request":$withApp}""").second.get("version")
        first.has("app").assert().isTrue()

        val (status, again) = explain("""{"request":$withApp,"at":$first}""")

        status.assert().describedAs("$again").isEqualTo(200)
        again.get("version").assert().isEqualTo(first)
    }

    /** Strict input stays strict: `at` takes no `null`, however the output is written. */
    @Test
    fun `at still refuses a null member`() {
        val (status, problem) = explain("""{"request":$unmapped,"at":{"platform":1,"app":null,"tenant":1}}""")

        status.assert().isEqualTo(400)
        problem.get("detail").asString().assert().contains("[app] is not an integer")
        explain("""{"request":$unmapped,"at":{"platform":1,"tenant":1}}""").first.assert().isEqualTo(200)
    }
}
