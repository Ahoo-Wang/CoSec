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
 * plane publishes is one the previous data plane installs. Both reserve the same characters (DOC-15): a pattern one
 * refuses, the other refuses too, and an escape, a wildcard and a pattern each mean the same to both, decided through
 * each version's engine.
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
    fun `a pattern one version reserves, the other refuses too`() {
        val reserved =
            body(
                """[{"id":"p","statements":[{"effect":"deny","action":"*","resource":"http:path:/odata/${'$'}metadata"}]}]"""
            )
        for (side in listOf(Side.CURRENT, Side.PREVIOUS)) {
            side("document.compile", "key" to "tenant:acme", "body" to reserved).assert().describedAs("$side")
                .startsWith("refused:")
        }
    }

    /** A DENY written with an escape (DOC-15) denies the path it names in both versions, and nothing beside it. */
    @Test
    fun `an escaped DENY denies its path in both versions`() {
        val escaped =
            """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z",""" +
                """"policies":[{"id":"p","statements":[""" +
                """{"effect":"allow","action":"http:*","resource":"http:path:/odata/**"},""" +
                """{"effect":"deny","action":"*","resource":"http:path:/odata/\\${'$'}metadata"}]}],""" +
                """"bindings":[{"id":"anyone","subject":"*","app":"*","space":"*","policy":"p"}]}"""
        fun decide(side: Side, path: String) =
            side(
                "document.decide",
                "owner" to "acme",
                "body" to escaped,
                "principal" to "alice",
                "action" to "http:GET",
                "resource" to "http:$path"
            )
        for (side in listOf(Side.CURRENT, Side.PREVIOUS)) {
            decide(side, "/odata/${'$'}metadata").assert().describedAs("$side").isEqualTo("deny")
            decide(side, "/odata/Xmetadata").assert().describedAs("$side").isEqualTo("permit")
            decide(side, "/odata/orders").assert().describedAs("$side").isEqualTo("permit")
        }
    }

    private fun matches(side: Side, kind: String, pattern: String, values: List<String>): String =
        side("pattern.matches", "kind" to kind, "pattern" to pattern, "values" to values.joinToString("\n"))

    private val paths = listOf(
        "/odata/${'$'}metadata",
        "/odata/Xmetadata",
        "/odata/metadata",
        "/odata/XYmetadata",
        "/odata/a/metadata"
    )
        .map { "http:path:$it" }
    private val ids = listOf("doc/a(b", "doc/axb", "doc/ab", "doc/a/b", "doc/a\\(b")
    private val actions = listOf("order:a(b", "order:a?b", "order:ab", "pay:x", "order:a\\(b")

    @Test
    fun `a wildcard matches alike in both versions, and one within a segment or a verb is refused by both`() {
        // In a path, ? is one character and * any within the segment, in both.
        for ((pattern, expected) in listOf("/odata/?metadata" to "ok:11000", "/odata/*metadata" to "ok:11110")) {
            for (side in listOf(Side.CURRENT, Side.PREVIOUS)) {
                matches(
                    side,
                    "resource",
                    "http:path:$pattern",
                    paths
                ).assert().describedAs("$side, $pattern").isEqualTo(expected)
            }
        }
        // In a typed id and an action, a wildcard stands for a whole segment, verb or action, in both.
        for (side in listOf(Side.CURRENT, Side.PREVIOUS)) {
            matches(side, "resource", "doc/*", ids).assert().describedAs("$side").isEqualTo("ok:11101")
            matches(side, "action", "order:*", actions).assert().describedAs("$side").isEqualTo("ok:11101")
            matches(side, "action", "*", actions).assert().describedAs("$side").isEqualTo("ok:11111")
            // A wildcard within a segment or a verb is refused by both: no narrower bridge there.
            matches(side, "resource", "doc/a?b", ids).assert().describedAs("$side").startsWith("refused:")
            matches(side, "resource", "doc/a*", ids).assert().describedAs("$side").startsWith("refused:")
            matches(side, "action", "order:a*", actions).assert().describedAs("$side").startsWith("refused:")
        }
        // A ? in a verb is reserved by both (DOC-15).
        for (side in listOf(Side.CURRENT, Side.PREVIOUS)) {
            matches(side, "action", "order:a?b", actions).assert().describedAs("$side").startsWith("refused:")
        }
    }

    @Test
    fun `an escape is the character to both versions`() {
        for (side in listOf(Side.CURRENT, Side.PREVIOUS)) {
            matches(side, "resource", "http:path:/odata/\\${'$'}metadata", paths).assert().describedAs("$side")
                .isEqualTo("ok:10000")
            matches(side, "resource", "doc/a\\(b", ids).assert().describedAs("$side").isEqualTo("ok:10000")
            matches(side, "action", "order:a\\(b", actions).assert().describedAs("$side").isEqualTo("ok:10000")
        }
    }

    /**
     * A DENY or a rate limit stored while 8.0.0-beta.8 ran, whose pattern holds a backslash beta.8 read as a character,
     * as the upgrade notes have it rewritten (review F-1): the stored pattern kept and its exact form, the backslash
     * escaped too, added beside it. Both versions compile it alike and deny what beta.8 denied, and the stored pattern,
     * now an escape, a little more. In an id, a type and an action alike.
     */
    @Test
    fun `a backslash DENY kept beside its exact form denies what was meant in both versions`() {
        val body =
            """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z",""" +
                """"policies":[{"id":"p","statements":[""" +
                """{"effect":"allow","action":["doc:*","svc:*"]},""" +
                """{"effect":"deny","action":"doc:read","resource":["doc/a\\(b","doc/a\\\\\\(b"]},""" +
                """{"effect":"deny","action":"doc:read","resource":["do\\(c/x","do\\\\\\(c/x"]},""" +
                """{"effect":"deny","action":["svc:re\\${'$'}ad","svc:re\\\\\\${'$'}ad"]}]}],""" +
                """"bindings":[{"id":"anyone","subject":"*","app":"*","space":"*","policy":"p"}]}"""
        for (side in listOf(Side.CURRENT, Side.PREVIOUS)) {
            side(
                "document.compile",
                "key" to "tenant:acme",
                "body" to body
            ).assert().describedAs("$side").startsWith("ok:")
            fun decide(action: String, resource: String) = side(
                "document.decide",
                "owner" to "acme",
                "body" to body,
                "principal" to "alice",
                "action" to action,
                "resource" to resource,
            )
            // What beta.8 stored the pattern for: the id, the type and the verb that hold the backslash.
            decide("doc:read", "doc/a\\(b").assert().describedAs("$side, id").isEqualTo("deny")
            decide("doc:read", "do\\(c/x").assert().describedAs("$side, type").isEqualTo("deny")
            decide("svc:re\\${'$'}ad", "any/x").assert().describedAs("$side, verb").isEqualTo("deny")
            decide("doc:write", "doc/a\\(b").assert().describedAs("$side, another action").isEqualTo("permit")
        }
        // The stored pattern denies a little more, its backslash read as an escape.
        for (side in listOf(Side.CURRENT, Side.PREVIOUS)) {
            side(
                "document.decide",
                "owner" to "acme",
                "body" to body,
                "principal" to "alice",
                "action" to "doc:read",
                "resource" to "doc/a(b",
            ).assert().describedAs("$side").isEqualTo("deny")
        }
    }
}
