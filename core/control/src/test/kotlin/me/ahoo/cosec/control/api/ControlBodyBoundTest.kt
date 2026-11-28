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
import me.ahoo.cosec.control.lifecycle.ControlDerived
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.lang.management.ManagementFactory

/**
 * DR1-F-3: a body costs about its size until a route has authorized what it asks. Only the routes that carry a document
 * (a proposal, an `:apply`, their checks) read up to [ControlDerived.MAX_BODY_BYTES]; every other route reads at most
 * [ControlDerived.SMALL_BODY_BYTES] and refuses a larger body with a 400 before it is parsed into a tree.
 */
class ControlBodyBoundTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin", "ops@team-a"))

    private fun post(path: String, body: ByteArray): Pair<Int, JsonNode> = runBlocking {
        val response = http.handle(ControlHttpRequest("POST", path, emptyMap(), body), alice)
        response.status to CoSecJson.mapper.readTree(response.body)
    }

    /** A JSON array of empty objects, [size] bytes: the body a parser turns into the most nodes for its size. */
    private fun objects(size: Int): ByteArray {
        val text = StringBuilder(size).append('[')
        while (text.length < size - 3) text.append("{},")
        return text.append("{}]").toString().encodeToByteArray()
    }

    @Test
    fun `a large body to a route that carries no document is a 400 before it is parsed`() {
        val body = objects(1024 * 1024)
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        for (path in listOf(
            "/simulate",
            "/explain",
            "/analyze",
            "/changes/c1:approve",
            "/documents/tenant:acme:rollback"
        )) {
            val before = threads.currentThreadAllocatedBytes
            val (status, problem) = post(path, body)
            val allocated = threads.currentThreadAllocatedBytes - before
            status.assert().describedAs(path).isEqualTo(400)
            problem.get("detail").asString().assert().describedAs(path)
                .isEqualTo(
                    "The request body is ${body.size} bytes, over the ${ControlDerived.SMALL_BODY_BYTES} this route reads."
                )
            // Parsed, a megabyte of empty objects is hundreds of thousands of nodes; refused first, it is its own bytes.
            allocated.assert().describedAs(path).isLessThan(body.size.toLong())
        }
    }

    @Test
    fun `a body at the small bound is read, one byte more is not`() {
        val padded = { size: Int -> ("{\"padding\":\"" + "x".repeat(size - 14) + "\"}").encodeToByteArray() }
        val within = padded(ControlDerived.SMALL_BODY_BYTES.toInt())
        within.size.assert().isEqualTo(ControlDerived.SMALL_BODY_BYTES.toInt())
        // Read and parsed: refused for what it says, not for its size.
        post("/simulate", within).second.get("detail").asString().assert().doesNotContain("bytes, over")
        post("/simulate", padded(ControlDerived.SMALL_BODY_BYTES.toInt() + 1)).second.get("detail").asString()
            .assert().contains("bytes, over")
    }

    @Test
    fun `the routes that carry a document still read up to the document's bound`() {
        val body = "x".repeat(ControlDerived.SMALL_BODY_BYTES.toInt() * 2).encodeToByteArray()
        for (path in listOf(
            "/changes",
            "/changes:check",
            "/documents/tenant:acme:apply",
            "/documents/tenant:acme:check"
        )) {
            val (status, problem) = post(path, body)
            status.assert().describedAs(path).isEqualTo(400)
            // Parsed, and not JSON: the size was not what refused it.
            problem.get(
                "detail"
            ).asString().assert().describedAs(path).isEqualTo("The request body is not JSON, or holds a key twice.")
        }
    }

    @Test
    fun `the small bound is eight request headers' worth`() {
        ControlDerived.SMALL_BODY_BYTES.assert().isEqualTo(64L * 1024)
    }
}
