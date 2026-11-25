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

package me.ahoo.cosec.compat

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * MIX-16 and MIX-17, the control API between an application's `control-sdk` and a control plane of another version
 * (CTL-76, the rule `TypedWireTest` holds): requests are read strictly, so an operation either version's client writes
 * is read alike by either version's control plane, and a member neither knows is refused; answers are read leniently,
 * a member the client does not know ignored, except a document's entries, which are read strictly.
 */
class ControlApiCompatTest {
    private val change = """{"format":1,"id":"c1","key":"tenant:acme","base":{"revision":0},"operations":[],""" +
        """"author":{"tenant":"acme","principal":"alice"},"note":"n","kind":"PUBLISH","state":"CHECKED","findings":[],""" +
        """"checkedWith":{},"createdAt":"2026-11-01T00:00:00Z","updatedAt":"2026-11-01T00:00:00Z"}"""

    private val document = """{"schema":2,"revision":3,"publishedAt":"2026-11-01T00:00:00Z",""" +
        """"policies":[{"id":"orders","statements":[{"effect":"allow","action":"order:read","resource":"order/**"}]}]}"""

    @Test
    fun `a proposal either version's client writes, either version's control plane reads alike`() {
        for ((client, plane) in PAIRS) {
            val body = client("sdk.propose")
            val read = plane("plane.operations", "key" to "tenant:acme", "body" to body)
            read.assert().describedAs("$client -> $plane").doesNotContain("refused:")
            read.lines().size.assert().isEqualTo(3)
            read.assert().describedAs(
                "$client -> $plane"
            ).isEqualTo(Side.CURRENT("plane.operations", "key" to "tenant:acme", "body" to body))
        }
    }

    @Test
    fun `an operation with a member a control plane does not know is refused by it`() {
        val body = Side.CURRENT("sdk.propose").replaceFirst("{\"op\":", "{\"later\":1,\"op\":")
        for (plane in listOf(Side.CURRENT, Side.PREVIOUS)) {
            plane(
                "plane.operations",
                "key" to "tenant:acme",
                "body" to body
            ).assert().describedAs("$plane").isEqualTo("refused:format")
        }
    }

    @Test
    fun `an answer either version's control plane gives, either version's client reads alike, ignoring a later member`() {
        for ((plane, client) in PAIRS) {
            val answer = plane("plane.answer", "record" to change)
            val read = client("sdk.change", "answer" to answer)
            read.assert().describedAs("$plane -> $client").doesNotStartWith("refused:")
            read.assert().isEqualTo(Side.CURRENT("sdk.change", "answer" to answer))
            client(
                "sdk.change",
                "answer" to HeaderJson.adding(answer, "\"later\":1")
            ).assert().describedAs("$client").isEqualTo(read)
        }
    }

    @Test
    fun `a document's entries in an answer are read strictly by every client`() {
        for (client in listOf(Side.CURRENT, Side.PREVIOUS)) {
            client(
                "sdk.document",
                "key" to "tenant:acme",
                "answer" to document
            ).assert().describedAs("$client").doesNotStartWith("refused:")
            val later = document.replace("{\"id\":\"orders\",", "{\"id\":\"orders\",\"later\":1,")
            client(
                "sdk.document",
                "key" to "tenant:acme",
                "answer" to later
            ).assert().describedAs("$client").startsWith("refused:")
        }
    }
}
