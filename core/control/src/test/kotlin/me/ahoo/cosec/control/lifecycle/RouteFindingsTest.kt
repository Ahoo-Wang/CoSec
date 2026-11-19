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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.Severity
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.document.route.Route
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** UC-1: the `ROUTES` warning lists the http: statements a mapped route of the services escapes, or says none were given. */
class RouteFindingsTest {
    private fun compiled(policies: String) = PolicyDocumentCompiler().compile(
        SnapshotKey.Platform,
        SnapshotDocuments.read(
            """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","policies":$policies,"bindings":[]}""".encodeToByteArray(),
        ),
    )

    private val adminDeny = """[{"id":"deny-admin","statements":[{"effect":"deny","action":"http:*","resource":"http:path:/admin/**"}]}]"""

    private fun routes(count: Int) = RouteMap.of(
        (1..count).map {
            RouteMap.Located(
                Route("DELETE", "/admin/r$it/{id}", "wms:r$it.purge", "r$it/{id}"),
                "wms.json"
            )
        },
    )

    private fun findings(policies: String, routes: RouteMap?) =
        ArrayList<Finding>().also { RouteFindings.check(compiled(policies), routes, it) }

    @Test
    fun `escapes are one warning with the count and the first five`() {
        val found = findings(adminDeny, routes(7)).single()
        found.check.assert().isEqualTo("ROUTES")
        found.severity.assert().isEqualTo(Severity.WARNING)
        found.message.assert().startsWith(
            "7 mapped route(s) escape http: statements"
        ).contains("DENY deny-admin#0 escapes DELETE /admin/r1/{id}")
            .contains("and 2 more").contains("Write each statement for the mapped action too")
        findings(adminDeny, routes(1)).single().message.assert().doesNotContain("more")
    }

    @Test
    fun `without route maps it says so, and nothing to compare says nothing`() {
        findings(adminDeny, null).single().message.assert().contains("no route maps were given")
        findings("""[{"id":"freeze","statements":[{"effect":"deny","action":"*"}]}]""", null).assert().isEmpty()
        findings(
            """[{"id":"d","statements":[{"effect":"deny","action":"http:*","resource":"http:path:/billing/**"}]}]""",
            routes(2)
        )
            .assert().isEmpty()
    }
}
