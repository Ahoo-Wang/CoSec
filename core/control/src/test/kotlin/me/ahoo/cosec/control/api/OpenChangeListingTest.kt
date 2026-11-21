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

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * `GET /changes` (R2C-4). CTL-55: a listing shows a change only if `GET /changes/{id}` would show it to the caller, and
 * a page tells nothing of what it leaves out. CTL-54: while the open-change sets are not kept, it is a 503 and no
 * revision is reclaimed.
 */
class OpenChangeListingTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin", "ops@team-a"))
    private val mallory = fixture.engine.security(ControlPlaneTest.GLOBEX, "mallory", roles = listOf("admin"))
    private val plato = fixture.engine.security(TenantId.PLATFORM, "plato")

    private fun list(query: Map<String, String>, security: RequestSecurity): Pair<Int, JsonNode> = runBlocking {
        val response = http.handle(ControlHttpRequest("GET", "/changes", query, null), security)
        response.status to CoSecJson.mapper.readTree(response.body)
    }

    private fun ids(body: JsonNode): List<String> = body.get("changes").values().map { it.get("id").asString() }

    private fun proposed(): List<String> = runBlocking {
        fixture.memory.keepOpenChanges()
        listOf(
            fixture.changes.propose(fixture.alice, SnapshotKey.Tenant("acme"), 1, emptyList(), "one").id.value,
            fixture.changes.propose(fixture.alice, SnapshotKey.Tenant("acme"), 1, emptyList(), "two").id.value,
        )
    }

    @Test
    fun `a listing by document or by state shows the caller's tenant only, a summary of each change`() {
        val acme = proposed()
        val (status, byDocument) = list(mapOf("document" to "tenant:acme"), alice)
        status.assert().isEqualTo(200)
        ids(byDocument).assert().containsExactlyElementsOf(acme)
        val one = byDocument.get("changes").first()
        one.get("state").asString().assert().isEqualTo("CHECKED")
        one.has("checkedWith").assert().describedAs("a summary of its own, not the stored record (R2C-11)").isFalse()
        ids(list(mapOf("state" to "CHECKED"), alice).second).assert().containsExactlyElementsOf(acme)
        ids(list(mapOf("state" to "CHECKED"), plato).second).assert().containsExactlyElementsOf(acme)
    }

    @Test
    fun `another tenant's changes are left out, and the page says nothing of them - CTL-55`() {
        proposed()
        for (query in listOf(mapOf("document" to "tenant:acme"), mapOf("state" to "CHECKED"))) {
            val (status, body) = list(query, mallory)
            status.assert().isEqualTo(200)
            ids(body).assert().isEmpty()
            body.get("next").isNull.assert().isTrue()
            body.propertyNames().toSet().assert().isEqualTo(setOf("changes", "next"))
        }
    }

    @Test
    fun `a page is cut after what the caller may see, and the next one goes on from it`() {
        val acme = proposed()
        val (_, first) = list(mapOf("state" to "CHECKED", "limit" to "1"), alice)
        ids(first).assert().containsExactly(acme[0])
        val (_, second) = list(
            mapOf("state" to "CHECKED", "limit" to "1", "after" to first.get("next").asString()),
            alice
        )
        ids(second).assert().containsExactly(acme[1])
        second.get("next").isNull.assert().isTrue()
    }

    /**
     * A page cut before the filter would name in its cursor the last change it read, acme's here, and a cursor followed
     * by an empty page would tell of changes after it (`CTL-55`).
     */
    @Test
    fun `a cursor names only a change the caller sees, and only when one follows it - CTL-55`() {
        val acme = proposed()
        val globex = runBlocking {
            fixture.changes.propose(fixture.mallory, SnapshotKey.Tenant("globex"), 1, emptyList(), "three").id.value
        }
        val (_, own) = list(mapOf("state" to "CHECKED", "limit" to "1"), mallory)
        ids(own).assert().containsExactly(globex)
        own.get("next").isNull.assert().isTrue()
        own.toString().assert().doesNotContain("acme")
        val after = ListingCursor.CHANGES_BY_STATE.encode("tenant:acme#${acme[0]}")
        val (_, last) = list(mapOf("state" to "CHECKED", "limit" to "1", "after" to after), alice)
        ids(last).assert().containsExactly(acme[1])
        last.get("next").isNull.assert().describedAs("globex's change follows, unseen").isTrue()
    }

    @Test
    fun `a listing by document pages by id and narrows by state`() {
        val acme = proposed().sorted()
        val (_, first) = list(mapOf("document" to "tenant:acme", "limit" to "1"), alice)
        ids(first).assert().containsExactly(acme[0])
        val next = first.get("next").asString()
        ListingCursor.CHANGES_OF_DOCUMENT.decode(next).assert().isEqualTo(acme[0])
        val (_, second) = list(mapOf("document" to "tenant:acme", "limit" to "1", "after" to next), alice)
        ids(second).assert().containsExactly(acme[1])
        second.get("next").isNull.assert().isTrue()
        ids(list(mapOf("document" to "tenant:acme", "state" to "SHADOWING"), alice).second).assert().isEmpty()
        ids(list(mapOf("document" to "tenant:acme", "state" to "CHECKED"), alice).second).assert()
            .containsExactlyElementsOf(acme)
    }

    @Test
    fun `a member of the tenant who may not read its changes sees none, a viewer sees them - CTL-55`() {
        val acme = proposed()
        val carol = fixture.engine.security(ControlPlaneTest.ACME, "carol", roles = emptyList())
        val vera = fixture.engine.security(ControlPlaneTest.ACME, "vera", roles = listOf("viewer"))
        for (query in listOf(mapOf("document" to "tenant:acme"), mapOf("state" to "CHECKED"))) {
            val (status, body) = list(query, carol)
            status.assert().isEqualTo(200)
            ids(body).assert().isEmpty()
            body.get("next").isNull.assert().isTrue()
            ids(list(query, vera).second).assert().containsExactlyInAnyOrderElementsOf(acme)
        }
    }

    @Test
    fun `a listing that names neither a document nor a state is refused`() {
        list(emptyMap(), alice).first.assert().isEqualTo(400)
        list(mapOf("state" to "PUBLISHED"), alice).first.assert().isEqualTo(400)
        list(mapOf("state" to "CHECKED", "limit" to "0"), alice).first.assert().isEqualTo(400)
    }

    @Test
    fun `while the old list is there the listing is a 503 and nothing is reclaimed - CTL-54`() = runBlocking<Unit> {
        proposed()
        fixture.memory.legacyOpenList = true
        list(mapOf("state" to "CHECKED"), alice).first.assert().isEqualTo(503)
        fixture.maintenance.reclaim().assert().isEmpty()
        fixture.memory.openChangesKept().assert().isFalse()
    }
}
