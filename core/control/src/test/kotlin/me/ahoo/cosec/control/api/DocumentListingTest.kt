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
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * `CTL-68`: `GET /documents?kind=` lists which tenant or app documents there are, from the store's document list: all of
 * them to a caller holding `cosec:read` on the layer (`doc/tenant`, `doc/app`), its own to a tenant's principal that may
 * read it, and to anyone else a 403 that tells nothing; on the operators' port and the service port alike. A kind other
 * than tenant or app is a 400, a store that cannot answer a 503.
 */
class DocumentListingTest {
    private val fixture = ControlPlaneTest()
    private val operators = ControlHttp(fixture.plane)
    private val services = ControlHttp(fixture.plane, ControlProfile.SERVICE)
    private val plato = fixture.engine.security(TenantId.PLATFORM, "plato")
    private val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin", "ops@team-a"))

    /** A platform principal bound to nothing: no `cosec:read` on any document or layer. */
    private val pia = fixture.engine.security(TenantId.PLATFORM, "pia")

    /** An acme principal with no role: it may not read its own tenant's document. */
    private val eve = fixture.engine.security(ControlPlaneTest.ACME, "eve")
    private val vera = fixture.engine.security(ControlPlaneTest.ACME, "vera", roles = listOf("viewer"))

    private fun list(
        security: RequestSecurity,
        query: Map<String, String>,
        http: ControlHttp = operators,
    ): Pair<Int, JsonNode> = runBlocking {
        val response = http.handle(ControlHttpRequest("GET", "/documents", query), security)
        response.status to CoSecJson.mapper.readTree(response.body)
    }

    private fun keys(page: JsonNode): List<String> = page.get("documents").iterator().asSequence()
        .map { it.get("key").asString() }.toList()

    @Test
    fun `the documents of a kind are listed for one who may read them all - CTL-68`() {
        val (status, page) = list(plato, mapOf("kind" to "tenant"))
        status.assert().isEqualTo(200)
        keys(page).assert().containsExactly("tenant:acme", "tenant:globex")
        page.get("next").isNull.assert().isTrue()
        // The service port answers the same.
        list(plato, mapOf("kind" to "tenant"), services).second.assert().isEqualTo(page)
    }

    @Test
    fun `a tenant's principal sees its own tenant's document if it may read it, and nothing else - CTL-68`() {
        keys(list(alice, mapOf("kind" to "tenant")).second).assert().containsExactly("tenant:acme")
        keys(list(vera, mapOf("kind" to "tenant")).second).assert().containsExactly("tenant:acme")
        keys(list(alice, mapOf("kind" to "tenant"), services).second).assert().containsExactly("tenant:acme")
    }

    @Test
    fun `anyone else is refused as the policies refuse, telling nothing of which documents exist - CTL-68`() {
        for ((caller, kind) in listOf(pia to "tenant", pia to "app", eve to "tenant", alice to "app")) {
            val (status, problem) = list(caller, mapOf("kind" to kind))
            status.assert().describedAs("$kind").isEqualTo(403)
            problem.has("detail").assert().isFalse()
        }
    }

    @Test
    fun `a page is cut after the filter, and its cursor names the last document shown - CTL-68`() {
        val (_, first) = list(plato, mapOf("kind" to "tenant", "limit" to "1"))
        keys(first).assert().containsExactly("tenant:acme")
        first.get("next").asString().assert().isEqualTo("tenant:acme")
        val (_, second) = list(plato, mapOf("kind" to "tenant", "limit" to "1", "after" to "tenant:acme"))
        keys(second).assert().containsExactly("tenant:globex")
        second.get("next").isNull.assert().isTrue()
        // A caller who sees one document gets it on one page, whatever the others are.
        list(alice, mapOf("kind" to "tenant", "limit" to "1")).second.get("next").isNull.assert().isTrue()
    }

    @Test
    fun `a kind other than tenant or app is a 400 - CTL-68`() {
        for (query in listOf(mapOf("kind" to "platform"), emptyMap(), mapOf("kind" to "Tenant"))) {
            list(plato, query).first.assert().describedAs("$query").isEqualTo(400)
        }
        list(plato, mapOf("kind" to "tenant", "limit" to "0")).first.assert().isEqualTo(400)
        list(plato, mapOf("kind" to "tenant", "limit" to "many")).first.assert().isEqualTo(400)
        list(plato, mapOf("kind" to "app")).first.assert().isEqualTo(200)
    }

    @Test
    fun `a store that cannot answer is a 503, never an empty page - CTL-68`() {
        fixture.down = true
        list(plato, mapOf("kind" to "tenant")).first.assert().isEqualTo(503)
        list(plato, mapOf("kind" to "tenant"), services).first.assert().isEqualTo(503)
    }
}
