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
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * `CTL-78`: a check answers what the proposal it stands for would, by the proposal's own steps, and stops before the last:
 * the same state, findings, escalations, base and candidate; nothing stored, no slot taken, no change event written; and
 * it is decided as the proposal would be, so it tells a caller no more than the proposal would (no oracle for what a
 * document holds).
 */
class ChangeCheckTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val service = ControlHttp(fixture.plane, ControlProfile.SERVICE)
    private val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin", "ops@team-a"))
    private val mallory = fixture.engine.security(ControlPlaneTest.GLOBEX, "mallory", roles = listOf("admin"))
    private val acme = SnapshotKey.Tenant("acme")

    init {
        runBlocking { fixture.memory.keepOpenChanges() }
    }

    private fun call(
        path: String,
        body: String,
        security: RequestSecurity = alice,
        on: ControlHttp = http,
    ): Pair<Int, JsonNode> = runBlocking {
        val response = on.handle(ControlHttpRequest("POST", path, emptyMap(), body.encodeToByteArray()), security)
        response.status to CoSecJson.mapper.readTree(response.body)
    }

    private fun proposal(base: Long, vararg operations: String) =
        """{"document":"tenant:acme","base":$base,"note":"n","operations":[${operations.joinToString(",")}]}"""

    private fun policy(id: String, statement: String = """{"effect":"allow","action":"order:read"}""") =
        """{"op":"putPolicy","policy":{"id":"$id","statements":[$statement]}}"""

    private fun binding(id: String, role: String) =
        """{"op":"putBinding","binding":{"id":"$id","subject":{"role":"$role"},"app":"order","space":"*","policy":"$id"}}"""

    /** What a check answers of a proposal's answer: the members it writes. */
    private fun asChecked(answer: JsonNode): JsonNode = (answer as ObjectNode).deepCopy().apply {
        retain(ChangeCheckAnswer.MEMBERS)
    }

    /** What the store holds of acme's document: its head, open changes, slot and candidate; and the events delivered. */
    private fun state(): List<Any?> = runBlocking {
        fixture.listeners.awaitDelivered()
        listOf(
            fixture.revisions.head(acme)?.digest,
            fixture.memory.openChanges(acme).map { it.id },
            fixture.memory.stage(acme)?.value?.holder,
            fixture.memory.candidate(acme)?.window,
            fixture.events.size,
        )
    }

    @Test
    fun `a check answers what the proposal would, clean, failing and escalating alike - CTL-78`() {
        val head = runBlocking { fixture.revisions.head(acme) }!!.revision
        listOf(
            proposal(head, policy("clean")),
            // A finding: the policy a binding names does not exist.
            proposal(head, binding("dangling", "viewer")),
            // A grant that waits for a person: what the proposal escalates, the check escalates.
            proposal(head, policy("readers"), binding("readers", "viewer")),
        ).forEach { body ->
            val (checkStatus, checked) = call("/changes:check", body)
            val (proposeStatus, proposed) = call("/changes", body)
            checkStatus.assert().isEqualTo(200)
            proposeStatus.assert().isEqualTo(201)
            checked.assert().describedAs(body).isEqualTo(asChecked(proposed))
            // Nothing that would name a change: the check made none.
            checked.has("id").assert().isFalse()
        }
        // The three are what they stand for: clean, with findings, with escalations.
        call("/changes:check", proposal(head, policy("clean"))).second.get("state").asString().assert().isEqualTo("CHECKED")
        val failing = call("/changes:check", proposal(head, binding("dangling", "viewer"))).second
        failing.get("state").asString().assert().isEqualTo("DRAFT")
        failing.get("findings").isEmpty.assert().isFalse()
        val escalating = call("/changes:check", proposal(head, policy("readers"), binding("readers", "viewer"))).second
        escalating.get("escalations")?.isEmpty.assert().isEqualTo(false)
    }

    @Test
    fun `a check of an apply answers what the apply would - CTL-78`() {
        val head = runBlocking { fixture.revisions.head(acme) }!!.revision
        val body = """{"mode":"MERGE","note":"apply","document":{"schema":2,"revision":$head,""" +
            """"publishedAt":"2026-11-01T00:00:00Z","policies":[{"id":"applied","statements":""" +
            """[{"effect":"allow","action":"order:list"}]}]}}"""
        val (checkStatus, checked) = call("/documents/tenant:acme:check", body)
        val (applyStatus, applied) = call("/documents/tenant:acme:apply", body)
        checkStatus.assert().isEqualTo(200)
        applyStatus.assert().isEqualTo(201)
        checked.assert().isEqualTo(asChecked(applied))
    }

    @Test
    fun `a check stores nothing, takes no slot and writes no change event - CTL-78`() {
        val head = runBlocking { fixture.revisions.head(acme) }!!.revision
        // Another change holds the slot: a check is not staged, so it neither waits for the slot nor takes it.
        val held = call("/changes", proposal(head, policy("held"))).second.get("id").asString()
        call("/changes/$held:skip-shadow", """{"reason":"hold"}""").first.assert().isEqualTo(200)
        val before = state()
        listOf(
            proposal(head, policy("clean")),
            proposal(head, binding("dangling", "viewer")),
            proposal(head, policy("readers"), binding("readers", "viewer")),
        ).forEach { call("/changes:check", it).first.assert().isEqualTo(200) }
        val document = """{"mode":"MERGE","note":"x","document":{"schema":2,"revision":$head,""" +
            """"publishedAt":"2026-11-01T00:00:00Z","policies":[]}}"""
        call("/documents/tenant:acme:check", document).first.assert().isEqualTo(200)
        state().assert().isEqualTo(before)
        runBlocking { fixture.memory.stage(acme)?.value?.holder?.value }.assert().isEqualTo(held)
    }

    @Test
    fun `a caller who may not propose may not check, and is refused alike - CTL-78`() {
        val head = runBlocking { fixture.revisions.head(acme) }!!.revision
        val before = state()
        // Another tenant's administrator: the same 403, whatever the document holds.
        val body = proposal(head, policy("trespass"))
        val checked = call("/changes:check", body, mallory)
        val proposed = call("/changes", body, mallory)
        checked.first.assert().isEqualTo(403)
        checked.first.assert().isEqualTo(proposed.first)
        checked.second.get("code").assert().isEqualTo(proposed.second.get("code"))
        checked.second.has("detail").assert().isFalse()
        // A stale base: the proposal's own conflict.
        val stale = call("/changes:check", proposal(head - 1, policy("stale")))
        stale.first.assert().isEqualTo(409)
        stale.second.get("conflict").asString().assert().isEqualTo("BASE_MOVED")
        // The platform document, on the service profile: no route there, for a check as for a proposal.
        val platform = """{"document":"platform","base":1,"note":"n","operations":[${policy("p")}]}"""
        call("/changes:check", platform, on = service).first.assert().isEqualTo(404)
        call("/changes", platform, on = service).first.assert().isEqualTo(404)
        call("/documents/platform:check", "{}", on = service).first.assert().isEqualTo(404)
        state().assert().isEqualTo(before)
    }
}
