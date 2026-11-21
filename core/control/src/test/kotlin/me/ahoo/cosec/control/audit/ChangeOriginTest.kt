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

package me.ahoo.cosec.control.audit

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ChangeSamples
import me.ahoo.cosec.control.api.ControlPlaneTest
import me.ahoo.cosec.control.change.ChangeOrigin
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** `CTL-57`: a change and its events say how the operator of each step was identified. */
class ChangeOriginTest {
    private val fixture = ControlPlaneTest()

    @Test
    fun `a change records how its author was identified, and each event how its step's operator was`() = runBlocking<Unit> {
        val change = fixture.plane.propose(
            fixture.alice,
            ControlPlaneTest.TENANT,
            1,
            listOf(Operation.PutPolicy(allow("more"))),
            "more",
        )
        // The fixture's host verifies a credential itself, so nothing was asserted; the credential is the one verified.
        val origin = change.origin.assert().isNotNull().actual()
        origin.asserted.assert().isFalse()
        origin.via?.method.assert().isEqualTo("test")
        origin.via?.credentialId.assert().isEqualTo("token-alice")
        origin.via?.requestId.assert().isNotNull()
        // Best-effort listeners hear it afterwards.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (fixture.events.isEmpty() && System.nanoTime() < deadline) {
            kotlinx.coroutines.delay(10)
        }
        fixture.events.map { it.origin }.assert().isNotEmpty().doesNotContainNull()
        fixture.events.first().origin.assert().isEqualTo(origin)
    }

    @Test
    fun `an origin is stored with its change and written with its event, with the members it has`() {
        val origin = ChangeOrigin(true, ChangeOrigin.Via("api-key", "svc-iam", "req-1"), "10.0.0.7")
        val stored = ChangeSamples.minimal.copy(origin = origin)
        val back = ChangeRequestCodec.decode(ChangeRequestCodec.encode(stored))
        back.origin.assert().isEqualTo(origin)
        // A change recorded before the member existed has none, and still reads.
        ChangeRequestCodec.decode(ChangeRequestCodec.encode(ChangeSamples.minimal)).origin.assert().isNull()

        val event = ChangeEvent(
            "e", java.time.Instant.EPOCH, ChangeEvent.Kind.PUBLISHED, stored.author, stored.id, stored.key,
            null, null, origin = origin,
        )
        val json = CoSecJson.mapper.readTree(ChangeEventJson.write(event)).get("origin")
        json.get("asserted").asBoolean().assert().isTrue()
        json.get("via").get("method").asString().assert().isEqualTo("api-key")
        json.get("via").get("credential").asString().assert().isEqualTo("svc-iam")
        json.get("via").get("request").asString().assert().isEqualTo("req-1")
        json.get("peer").asString().assert().isEqualTo("10.0.0.7")
        CoSecJson.mapper.readTree(ChangeEventJson.write(event.withoutOrigin())).has("origin").assert().isFalse()
    }

    private fun ChangeEvent.withoutOrigin() = ChangeEvent(id, time, kind, operator, change, document, base, candidate)
}
