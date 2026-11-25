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
 * previous one took as a literal, is refused by the current one, as the upgrade notes say. The bridge the notes and
 * `cosec-migrate check-patterns` give for the upgrade (a wildcard in place of the character) means the same in both,
 * while an escape does not: the previous version reads the backslash as a character.
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

    /**
     * A DENY written with an escape, the current version's (DOC-15): the current version denies the path it names. A
     * reader of 8.0.0-beta.8 compiles the same document, since it read a backslash as text before DOC-15 reserved it, to
     * a pattern that matches another path, and does not deny that one: the one reader for which an escape gives another
     * meaning, which the upgrade notes order around (MIX-11, beta.9 review C-1). From 8.0.0-beta.9 as the previous
     * release, both decide alike. Decided through each version's engine, not judged by compiling alone.
     */
    @Test
    fun `an escaped DENY denies its path on the current version, and beta_8 compiles it and does not`() {
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
        decide(Side.CURRENT, "/odata/${'$'}metadata").assert().isEqualTo("deny")
        decide(Side.CURRENT, "/odata/orders").assert().isEqualTo("permit")
        Side.PREVIOUS("document.compile", "key" to "tenant:acme", "body" to escaped).assert().startsWith("ok:")
        Baseline.from("8.0.0-beta.9", "the previous version denies the path an escaped DENY names") {
            decide(Side.PREVIOUS, "/odata/${'$'}metadata").assert().isEqualTo("deny")
        }
        if (!Baseline.reached("8.0.0-beta.9")) {
            decide(
                Side.PREVIOUS,
                "/odata/${'$'}metadata"
            ).assert().describedAs("beta.8 read the backslash as text").isEqualTo("permit")
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
    fun `the bridge for a reserved character matches alike in both versions, a wildcard that matches more`() {
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
        // A ? in a verb was a character to the previous version; the current one reserves it (DOC-15).
        matches(Side.PREVIOUS, "action", "order:a?b", actions).assert().isEqualTo("ok:01000")
        matches(Side.CURRENT, "action", "order:a?b", actions).assert().startsWith("refused:")
    }

    @Test
    fun `an escape is the character to the current version and a backslash and the character to the previous one`() {
        matches(Side.CURRENT, "resource", "http:path:/odata/\\${'$'}metadata", paths).assert().isEqualTo("ok:10000")
        matches(Side.PREVIOUS, "resource", "http:path:/odata/\\${'$'}metadata", paths).assert().isEqualTo("ok:00000")
        // In an id or an action the previous version matches another one, which an id or a verb can be.
        matches(Side.CURRENT, "resource", "doc/a\\(b", ids).assert().isEqualTo("ok:10000")
        matches(Side.PREVIOUS, "resource", "doc/a\\(b", ids).assert().isEqualTo("ok:00001")
        matches(Side.CURRENT, "action", "order:a\\(b", actions).assert().isEqualTo("ok:10000")
        matches(Side.PREVIOUS, "action", "order:a\\(b", actions).assert().isEqualTo("ok:00001")
    }

    /**
     * The stand-in for a DENY or a rate limit whose pattern holds a backslash, which beta.8 read as a character and the
     * current version reads as an escape (review F-1): the stored pattern kept and its exact form, the backslash escaped
     * too, added beside it. Both versions compile both, and each denies what was meant with one of the two; the other
     * denies a little more on the current version and matches nothing that was meant on beta.8. In an id, a type and an
     * action alike.
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
        // The stored pattern denies a little more on the current version, which reads its backslash as an escape.
        Side.CURRENT(
            "document.decide",
            "owner" to "acme",
            "body" to body,
            "principal" to "alice",
            "action" to "doc:read",
            "resource" to "doc/a(b",
        ).assert().isEqualTo("deny")
    }
}
