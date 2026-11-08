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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class)

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/** `POST /changes` reads the fields of a section operation as a document's own (`CTL-28`). */
class SectionFieldsHttpTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val plato = fixture.engine.security(TenantId.PLATFORM, "plato")

    private fun call(body: String): Pair<Int, JsonNode> = runBlocking {
        val response = http.handle(ControlHttpRequest("POST", "/changes", emptyMap(), body.encodeToByteArray()), plato)
        response.status to CoSecJson.mapper.readTree(response.body)
    }

    /** #1275: a section field of the wrong type is refused like the document's own, never made into another value. */
    @Test
    fun `a section field of the wrong type is a 400 and proposes nothing - #1275`() {
        fun proposal(operation: String) = """{"document":"platform","base":1,"operations":[$operation]}"""
        fun limits(maxNodes: String) =
            """{"op":"setLimits","limits":{"maxNodes":$maxNodes,"maxDepth":16,"maxInValues":64,""" +
                """"maxCidrBlocks":64,"maxLiteralBytes":4096,"maxCost":1024}}"""
        val before = fixture.ids.get()
        val malformed = listOf(
            """{"op":"setAttributes","attributes":{}}""",
            """{"op":"setAttributes","attributes":"mistyped"}""",
            """{"op":"setAttributes","attributes":null}""",
            """{"op":"setAttributes","attributes":[{"path":"ext.geo.country","type":"string","extra":1}]}""",
            """{"op":"setAttributes","attributes":[{"path":"geo.country","type":"string"}]}""",
            limits("64.5"),
            limits("\"64\""),
            limits("0"),
            limits("3000000000"),
            limits("true"),
            """{"op":"setLimits","limits":{"maxNodes":64}}""",
            """{"op":"setLimits","limits":[]}""",
            """{"op":"setLimits","limits":null}""",
        )
        for (operation in malformed) {
            val (status, body) = call(proposal(operation))
            status.assert().describedAs(operation).isEqualTo(400)
            body.get("detail").asString().assert().describedAs(operation).isNotBlank()
        }
        fixture.ids.get().assert().isEqualTo(before)
        val (attributes, declared) = call(
            proposal("""{"op":"setAttributes","attributes":[{"path":"ext.geo.country","type":"string"}]}""")
        )
        attributes.assert().isEqualTo(201)
        declared.get("operations").get(0).get("attributes").size().assert().isEqualTo(1)
        val (bounded, limited) = call(proposal(limits("64")))
        bounded.assert().isEqualTo(201)
        limited.get("operations").get(0).get("limits").get("maxNodes").asInt().assert().isEqualTo(64)
        call(proposal("""{"op":"setAttributes"}""")).first.assert().isEqualTo(201)
    }
}
