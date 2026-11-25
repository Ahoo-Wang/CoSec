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

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/** `DOC-13`, `DOC-14` through the control API's routes: a change whose document would not compile goes nowhere. */
class DocumentRefusalHttpTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin", "ops@team-a"))

    private fun call(method: String, path: String, body: String? = null): Pair<Int, JsonNode> = runBlocking {
        val response = http.handle(ControlHttpRequest(method, path, emptyMap(), body?.encodeToByteArray()), alice)
        response.status to CoSecJson.mapper.readTree(response.body)
    }

    /**
     * `DOC-13`, `DOC-14` on the control plane: a change whose document does not compile stays a draft whose findings name
     * each statement by its path, each a 422, never a 500; it moves no further. Its operations show the id as written,
     * in a JSON string; the finding shows it escaped.
     */
    @Test
    fun `a change with a deny matching the path undecoded or an id that would forge a line stays a draft naming it`() {
        val raw = """{"pattern":"http:path:/admin/**","decodeAndParseSegments":false}"""
        val proposal = """{"document":"tenant:acme","base":1,"note":"raw","operations":[""" +
            """{"op":"putPolicy","policy":{"id":"raw","statements":[{"effect":"deny","action":"http:*","resource":$raw}]}},""" +
            """{"op":"putPolicy","policy":{"id":"x\n2026-11-19 ERROR forged","statements":[]}}]}"""
        val (created, change) = call("POST", "/changes", proposal)
        created.assert().isEqualTo(201)
        change.get("state").asString().assert().isEqualTo("DRAFT")
        val nodes = change.get("findings").let { all -> (0 until all.size()).map(all::get) }
        nodes.map {
            Triple(
                it.get("check").asString(),
                it.get("path").asString(),
                it.get("status").asInt()
            )
        }.assert().containsExactlyInAnyOrder(
            Triple("COMPILE", "$.policies[1].statements[0].resource[0]", 422),
            Triple("COMPILE", "$.policies[2].id", 422),
        )
        nodes.map { it.get("message").asString() }.assert()
            .anyMatch { "DOC-14" in it && "remove the option" in it }
            .anyMatch { "[x\\u000A2026-11-19 ERROR forged]" in it }
        val (refused, problem) = call("POST", "/changes/${change.get("id").asString()}:shadow")
        refused.assert().isEqualTo(409)
        problem.get("detail").asString().assert().contains("is DRAFT")
    }
}
