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
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Escalation
import me.ahoo.cosec.control.change.Propagation
import me.ahoo.cosec.control.change.Severity
import me.ahoo.cosec.control.store.ChangePage
import me.ahoo.cosec.control.store.ListedChange
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.contract.ContractFiles
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * `CTL-61` (R2C-11, R2C-13): what the control API answers, as `contract/control/` states it, the way `contract/` states
 * the data plane's (`PIP-25`). The files are written from the answer codecs, never by hand: `change.json` and
 * `change-summary.json` each member of a change and of a listed change, with its JSON type and whether every answer
 * holds it; `propagation.json` the members of a revision's propagation; `vocabularies.json` the closed sets a client
 * reads; `routes.json` the change kinds each route that moves a change writes to the audit stream, so the step a route
 * names and the event a reader of the audit finds are one pair.
 *
 * What it does not cover: an operation inside a change is the document format's entry (`schema/`), and a decision's
 * members are pinned by their vocabularies only; the break-glass routes, which need a REQUIRED listener, are covered by
 * their own tests.
 */
class ClientContractTest {
    private val files = ContractFiles()

    @Test
    fun `a change answer holds exactly the members of its contract file`() {
        files.check("control/change.json", Shapes.of(ChangeSamples.all.map(ChangeAnswer::tree), OPAQUE))
    }

    @Test
    fun `a listed change holds exactly the members of its contract file`() {
        val page = ChangePage(
            ChangeSamples.all.map { ListedChange.Readable(it) } +
                ListedChange.Unreadable(ChangeId("c9"), SnapshotKey.Tenant("acme")),
            null,
        )
        val listed = ControlResponseJson.changes(page).get("changes").iterator().asSequence().toList()
        files.check("control/change-summary.json", Shapes.of(listed, emptySet()))
    }

    @Test
    fun `a propagation answer holds exactly the members of its contract file`() {
        val answer = ControlResponseJson.propagation(Propagation(2, 1, 2, 3, Instant.parse("2026-11-20T00:00:00Z")))
        files.check("control/propagation.json", Shapes.of(listOf(answer), emptySet()))
    }

    @Test
    fun `the closed sets of the control API are those of their contract file`() {
        val vocabularies = files.objectNode()
        fun put(name: String, values: List<String>) = vocabularies.putArray(name).apply { values.forEach(::add) }
        put("state", ChangeState.entries.map { it.name })
        put("kind", ProvenanceKind.entries.map { it.name })
        put("severity", Severity.entries.map { it.name })
        put("escalationReason", Escalation.Reason.entries.map { it.name })
        put("shadowOutcome", ShadowOutcome.entries.map { it.name })
        put("decision", ControlVocabulary.DECISIONS)
        put("cause", ControlVocabulary.CAUSES)
        put("reason", ControlVocabulary.REASONS)
        put("effect", ControlVocabulary.EFFECTS)
        put("outcome", ControlVocabulary.OUTCOMES)
        files.check("control/vocabularies.json", vocabularies)
    }

    @Test
    fun `each route that moves a change writes the change kinds of its contract file`() {
        files.check("control/routes.json", RouteKinds().record())
    }

    @Test
    fun `contract control holds only the files the code answers`() {
        files.only(
            "control",
            setOf("change.json", "change-summary.json", "propagation.json", "vocabularies.json", "routes.json"),
        )
    }

    /** Drives every route that moves a change through [ControlHttp] and records the change kinds each one audits. */
    private class RouteKinds {
        private val fixture = ControlPlaneTest()
        private val http = ControlHttp(fixture.plane)
        private val alice = fixture.engine.security(
            ControlPlaneTest.ACME,
            "alice",
            roles = listOf("admin", "ops@team-a")
        )
        private val plato = fixture.engine.security(TenantId.PLATFORM, "plato")
        private val routes = linkedMapOf<String, List<String>>()

        /** The revision of `tenant:acme` a proposal is based on: 2 once the first change published. */
        private var base = 1

        private fun call(route: String, path: String, body: String? = null, security: RequestSecurity = alice): JsonNode =
            runBlocking {
                fixture.listeners.awaitDelivered()
                fixture.events.clear()
                val response = http.handle(
                    ControlHttpRequest("POST", path, emptyMap(), body?.encodeToByteArray()),
                    security
                )
                response.status.assert().describedAs(route).isIn(200, 201)
                fixture.listeners.awaitDelivered()
                // Sorted: a best-effort listener receives a step's events in any order.
                routes[route] = fixture.events.map { it.kind.name }.sorted()
                CoSecJson.mapper.readTree(response.body)
            }

        private fun propose(note: String, operations: String = policy(note)): String =
            runBlocking {
                http.handle(
                    ControlHttpRequest("POST", "/changes", emptyMap(), proposal(note, operations).encodeToByteArray()),
                    alice
                )
            }
                .let { CoSecJson.mapper.readTree(it.body) }
                .let { it.get("id")?.asString() ?: error("$note: $it") }

        private fun shadowed(id: String) = runBlocking { fixture.changes.shadowed(ChangeId(id)) }

        fun record(): ObjectNode {
            val behind = propose("behind")
            val id = call("POST /changes", "/changes", proposal("more", policy("more"))).get("id").asString()
            call("POST /changes/{id}:shadow", "/changes/$id:shadow")
            shadowed(id)
            call("POST /changes/{id}:publish", "/changes/$id:publish")
            base = 2
            call("POST /changes/{id}:rebase", "/changes/$behind:rebase", "{}")
            // Each change that takes the document's staging slot leaves it before the next one needs it.
            val skipped = propose("skip")
            call("POST /changes/{id}:skip-shadow", "/changes/$skipped:skip-shadow", """{"reason":"hurry"}""")
            call("POST /changes/{id}:abandon", "/changes/$skipped:abandon", "{}")
            val rejected = waiting("reject")
            call("POST /changes/{id}:reject", "/changes/$rejected:reject", """{"reason":"no"}""", plato)
            val approved = waiting("approve")
            call(
                "POST /changes/{id}:approve",
                "/changes/$approved:approve",
                """{"candidate":"${candidate(approved)}"}""",
                plato
            )
            call(
                "POST /documents/{key}:apply",
                "/documents/tenant:acme:apply",
                """{"mode":"MERGE","note":"apply","document":{"schema":2,"revision":$base,""" +
                    """"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}}""",
            )
            call(
                "POST /documents/{key}:rollback",
                "/documents/tenant:acme:rollback",
                """{"revision":1,"note":"back"}"""
            )
            return files().also { node ->
                routes.forEach { (route, kinds) ->
                    node.putArray(
                        route
                    ).apply { kinds.forEach(::add) }
                }
            }
        }

        /** A change that needs a second person's approval, its window closed. */
        private fun waiting(note: String): String {
            val id = propose(
                note,
                """{"op":"putPolicy","policy":{"id":"$note","statements":""" +
                    """[{"effect":"allow","action":"order:read","resource":"order/{id}"}]}},""" +
                    """{"op":"putBinding","binding":{"id":"$note","subject":{"role":"viewer"},"app":"order",""" +
                    """"space":"*","policy":"$note"}}""",
            )
            runBlocking { http.handle(ControlHttpRequest("POST", "/changes/$id:shadow", emptyMap(), null), alice) }
            shadowed(id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
            return id
        }

        private fun candidate(id: String) = runBlocking { fixture.changes.get(ChangeId(id)) }.candidate

        private fun files() = CoSecJson.mapper.createObjectNode()

        private fun policy(id: String) =
            """{"op":"putPolicy","policy":{"id":"$id","statements":[{"effect":"allow","action":"order:read"}]}}"""

        private fun proposal(note: String, operations: String) =
            """{"document":"tenant:acme","base":$base,"note":"$note","operations":[$operations]}"""
    }

    private companion object {
        /** Members whose content another contract states: an operation is the document format's entry; counts are keyed by outcome. */
        val OPAQUE = setOf("operations[]", "shadow.closed.counts")
    }
}
