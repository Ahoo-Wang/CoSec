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
 * MIX-11, policy documents between versions (RFC 0014 R4): read strictly by both, so a document the current control
 * plane publishes is one the previous data plane installs; a pattern the current version reserves (DOC-15), which the
 * previous one took as a literal, is refused by the current one, as the upgrade notes say.
 */
class DocumentCompatTest {
    private fun body(
        policies: String
    ) = """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","policies":$policies}"""

    private val documents = mapOf(
        "tenant:acme" to body(
            """[{"id":"orders","statements":[{"effect":"allow","action":"order:*","resource":["order/**","http:path:/orders/*"]},""" +
                """{"effect":"deny","action":"order:delete","resource":"order/archived/**"}]}]"""
        ),
        "app:order" to body("""[{"id":"perm:order/read","statements":[{"effect":"allow","action":"order:read","resource":"order/**"}]}]"""),
    )

    @Test
    fun `a document either version compiles, the other compiles too`() {
        for ((key, document) in documents) {
            for (side in listOf(Side.CURRENT, Side.PREVIOUS)) {
                side(
                    "document.compile",
                    "key" to key,
                    "body" to document
                ).assert().describedAs("$side, $key").startsWith("ok:")
            }
        }
    }

    @Test
    fun `a pattern the current version reserves is refused by it, though the previous one took it as a literal`() {
        val reserved =
            body(
                """[{"id":"p","statements":[{"effect":"deny","action":"*","resource":"http:path:/odata/${'$'}metadata"}]}]"""
            )
        Side.CURRENT("document.compile", "key" to "tenant:acme", "body" to reserved).assert().startsWith("refused:")
        Side.PREVIOUS("document.compile", "key" to "tenant:acme", "body" to reserved).assert().startsWith("ok:")
    }
}
