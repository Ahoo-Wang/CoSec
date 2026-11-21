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
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.ControlResources
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeOrigin
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * `CTL-70`: a change's answer carries how its author was identified. Every reader of the change is told whether the
 * identity was only stated and by which method; the credential's id, the request's id and the connection's address go
 * only to who approves changes of its document or reads the platform's, and only on a permit.
 */
class ChangeOriginAnswerTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val viewer = fixture.engine.operator(ControlPlaneTest.ACME, "vera", roles = listOf("viewer"))

    /** alice's change, proposed from 10.0.0.7 with her token. */
    private val change: ChangeRequest = runBlocking {
        val author = Operator.of(fixture.alice.security!!, peer = "10.0.0.7")!!
        fixture.plane.propose(author, ControlPlaneTest.TENANT, 1, listOf(Operation.PutPolicy(allow("more"))), "more")
    }

    private fun answer(reader: Operator): JsonNode = runBlocking {
        val response = http.handle(
            ControlHttpRequest("GET", "/changes/${change.id.value}", emptyMap(), null),
            reader.security
        )
        response.status.assert().isEqualTo(200)
        CoSecJson.mapper.readTree(response.body)
    }

    private fun members(origin: JsonNode): List<String> = origin.properties().flatMap { (name, value) ->
        if (value.isObject) value.properties().map { "$name.${it.key}" } else listOf(name)
    }.sorted()

    @Test
    fun `a reader of the platform's document is given the author's whole origin - CTL-70`() {
        val origin = answer(fixture.plato).get("origin")
        origin.get("asserted").asBoolean().assert().isFalse()
        origin.get("via").get("method").asString().assert().isEqualTo("test")
        origin.get("via").get("credential").asString().assert().isEqualTo("token-alice")
        origin.get("via").get("request").asString().assert().isEqualTo(change.origin!!.via!!.requestId)
        origin.get("peer").asString().assert().isEqualTo("10.0.0.7")
    }

    @Test
    fun `a reader who neither approves nor reads the platform is told only asserted and the method - CTL-70`() {
        // The author, a tenant administrator who does not approve, and a viewer read the same short origin.
        for (reader in listOf(fixture.alice, viewer)) {
            val origin = answer(reader).get("origin")
            members(origin).assert().describedAs("$reader").containsExactly("asserted", "via.method")
            origin.get("asserted").asBoolean().assert().isFalse()
            origin.get("via").get("method").asString().assert().isEqualTo("test")
        }
    }

    @Test
    fun `an approver of the document is given the whole origin, and only a permit counts - CTL-70`() = runBlocking<Unit> {
        val tenant = ControlPlaneTest.TENANT
        fun plane(approve: ControlDecision, platformRead: ControlDecision) = DefaultControlPlane(
            fixture.changes,
            fixture.revisions,
            ChangeAuthority(
                ControlAuthorizer { _, action, resource ->
                    when {
                        action == ControlActions.APPROVE && resource.id == ControlResources.document(tenant).id -> approve
                        action == ControlActions.READ && resource.id == ControlResources.document(SnapshotKey.Platform).id ->
                            platformRead
                        else -> ControlDecision.DENY
                    }
                },
                ChangeOptions(shadow = IMMEDIATE_WINDOW),
                revocations = RevocationReader.NONE,
            ),
            Simulator(),
        )
        val deny = ControlDecision.DENY
        plane(ControlDecision.PERMIT, deny).readsWholeOrigin(fixture.alice, change).assert().isTrue()
        plane(deny, ControlDecision.PERMIT).readsWholeOrigin(fixture.plato, change).assert().isTrue()
        plane(deny, deny).readsWholeOrigin(fixture.plato, change).assert().isFalse()
        // An indeterminate decision is no permit: the short origin, never the whole one.
        val unknown = ControlDecision.INDETERMINATE
        plane(unknown, unknown).readsWholeOrigin(fixture.plato, change).assert().isFalse()
        plane(unknown, unknown).readsWholeOrigin(fixture.alice, change).assert().isFalse()
        // A tenant's operator never reads the platform's document, whatever an authorizer would say (the hard rule).
        plane(deny, ControlDecision.PERMIT).readsWholeOrigin(fixture.alice, change).assert().isFalse()
        // A plane that cannot tell gives the short origin.
        object : ControlPlane by fixture.plane {
            override suspend fun readsWholeOrigin(operator: Operator, change: ChangeRequest): Boolean =
                super.readsWholeOrigin(operator, change)
        }.readsWholeOrigin(fixture.plato, change).assert().isFalse()
    }

    @Test
    fun `the origin is written member by member, whole or short, and absent for a change no request made - CTL-70`() {
        val origin = ChangeOrigin(true, ChangeOrigin.Via("api-key", "svc-iam", "req-1"), "10.0.0.7")
        val stored = ChangeSamples.minimal.copy(origin = origin)
        members(ChangeAnswer.tree(stored, wholeOrigin = true).get("origin"))
            .assert().containsExactly("asserted", "peer", "via.credential", "via.method", "via.request")
        val short = ChangeAnswer.tree(stored).get("origin")
        members(short).assert().containsExactly("asserted", "via.method")
        short.get("asserted").asBoolean().assert().isTrue()
        short.get("via").get("method").asString().assert().isEqualTo("api-key")
        // Without a credential: no `via` at all, whoever reads.
        members(ChangeAnswer.tree(ChangeSamples.minimal.copy(origin = ChangeOrigin(true)), wholeOrigin = true).get("origin"))
            .assert().containsExactly("asserted")
        ChangeAnswer.tree(ChangeSamples.minimal, wholeOrigin = true).has("origin").assert().isFalse()
    }
}
