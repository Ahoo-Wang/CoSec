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

package me.ahoo.cosec.document.route

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.engine.httpStatements
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * UC-1: which DENY statements and rate limit rules a mapped route escapes. A request a route maps is decided as the
 * route's action on its resource, so a statement on `http:<METHOD>` and `http:path:` stops applying to it.
 */
class RouteEscapesTest {
    private val routes = RouteMap.of(
        listOf(
            RouteMap.Located(
                Route("DELETE", "/admin/warehouses/{id}", "wms:warehouse.purge", "warehouse/{id}"),
                "wms-routes.json"
            ),
            RouteMap.Located(Route("GET", "/orders/{id}", "order:read", "order/{id}"), "order-routes.json"),
            RouteMap.Located(Route("POST", "/api/orders", "order:create", "order"), "order-routes.json"),
        ),
    )

    private fun platform(policies: String, rateLimits: String = "[]") = PolicyDocumentCompiler().compile(
        SnapshotKey.Platform,
        SnapshotDocuments.read(
            (
                """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","policies":$policies,"bindings":[],""" +
                    """"rateLimits":$rateLimits}"""
                ).encodeToByteArray()
        ),
    )

    private fun escapes(policies: String, rateLimits: String = "[]") =
        routes.escapes(platform(policies, rateLimits).httpStatements())

    @Test
    fun `an http DENY a mapped route escapes is reported, with its route`() {
        val found = escapes(
            """[{"id":"deny-admin","statements":[{"effect":"deny","action":"http:*","resource":"http:path:/admin/**"}]}]"""
        )
        found.map { it.statement to "${it.route.method} ${it.route.path}" }.assert()
            .containsExactly("DENY deny-admin#0" to "DELETE /admin/warehouses/{id}")
        found.single().location.assert().isEqualTo("wms-routes.json")
        found.single().toString().assert().contains("wms:warehouse.purge on warehouse/{id}")
    }

    @Test
    fun `a statement that also applies to the mapped request does not escape`() {
        // Every action and every resource: it applies to the mapped request too, so it is not even compared.
        escapes("""[{"id":"freeze","statements":[{"effect":"deny","action":"*"}]}]""").assert().isEmpty()
        // Written for both forms, as route-maps.md shows.
        escapes(
            """[{"id":"both","statements":[{"effect":"deny","action":["http:*","wms:*"],""" +
                """"resource":["http:path:/admin/**","warehouse/**"]}]}]""",
        ).assert().isEmpty()
        // An ALLOW that stops applying only narrows.
        escapes(
            """[{"id":"open","statements":[{"effect":"allow","action":"http:*","resource":"http:path:/admin/**"}]}]"""
        )
            .assert().isEmpty()
    }

    @Test
    fun `another method or a disjoint path does not escape`() {
        escapes(
            """[{"id":"d","statements":[{"effect":"deny","action":"http:POST","resource":"http:path:/admin/**"}]}]"""
        ).assert().isEmpty()
        escapes(
            """[{"id":"d","statements":[{"effect":"deny","action":"http:*","resource":"http:path:/billing/**"}]}]"""
        ).assert().isEmpty()
    }

    @Test
    fun `a rate limit rule a mapped route escapes is reported`() {
        val found = escapes(
            "[]",
            """[{"id":"api-writes","action":"http:POST","resource":"http:path:/api/**","permitsPerSecond":5}]"""
        )
        found.map { it.statement }.assert().containsExactly("rate limit platform/api-writes")
        found.single().route.path.assert().isEqualTo("/api/orders")
    }

    /** `DOC-12` (R1B-3): an id is written as its document wrote it, and a line shows it escaped and bounded. */
    @Test
    fun `an id that would forge a line is shown escaped, and a long one cut`() {
        // Rate limit rule ids are letters, digits, '.', '_' and '-' already; a policy id is checked for nothing.
        val forged = escapes(
            """[{"id":"x\n2026-11-19 ERROR forged\u0007\u001b[31m","statements":[{"effect":"deny","action":"http:*",""" +
                """"resource":"http:path:/admin/**"}]}]""",
        ).single()
        forged.statement.assert().isEqualTo("DENY x\n2026-11-19 ERROR forged\u0007\u001b[31m#0")
        val line = forged.toString()
        line.none { it.isISOControl() }.assert().describedAs(line).isTrue()
        line.assert().startsWith("DENY x\\u000A2026-11-19 ERROR forged\\u0007\\u001B[31m#0 escapes DELETE")

        val long = "p".repeat(RouteEscape.MAX_PART * 2)
        val cut = escapes(
            """[{"id":"$long","statements":[{"effect":"deny","action":"http:*","resource":"http:path:/admin/**"}]}]"""
        ).single().toString()
        // The part is the statement's label, `DENY <id>#<index>`: it is cut, not the id alone.
        cut.assert().startsWith("DENY " + "p".repeat(RouteEscape.MAX_PART - "DENY ".length) + "… (263 characters) escapes")
        cut.assert().doesNotContain("p".repeat(RouteEscape.MAX_PART + 1))
        // A cut never leaves half of a surrogate pair, which a UTF-8 log would write as `?`.
        val pairs = "a" + "\uD83D\uDE00".repeat(RouteEscape.MAX_PART)
        RouteEscape.shown(pairs).let { shown -> shown.indices.none { Identifiers.isUnpairedSurrogateAt(shown, it) } }
            .assert().isTrue()
    }

    @Test
    fun `nothing to compare, nothing reported`() {
        RouteMap.EMPTY.escapes(
            platform("""[{"id":"d","statements":[{"effect":"deny","action":"http:*"}]}]""").httpStatements()
        )
            .assert().isEmpty()
        routes.escapes(emptyList()).assert().isEmpty()
    }
}
