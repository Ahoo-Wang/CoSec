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
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ConflictKind
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * `CTL-80`: a `409` of the control API names which conflict it is, in the problem's `conflict` member, so a caller tells
 * "read again and retry" from "wait" from "stop" without reading `detail`. No other status carries the member. A
 * `SLOT_HELD` names the change that holds the slot, `heldBy`, as its `detail` does; no other conflict names one.
 */
class ConflictKindTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin", "ops@team-a"))

    private fun call(method: String, path: String, body: String? = null): Pair<Int, JsonNode> = runBlocking {
        val response = http.handle(ControlHttpRequest(method, path, emptyMap(), body?.encodeToByteArray()), alice)
        response.status to CoSecJson.mapper.readTree(response.body)
    }

    private fun proposal(id: String, base: Long) = """{"document":"tenant:acme","base":$base,"note":"$id",""" +
        """"operations":[{"op":"putPolicy","policy":{"id":"$id","statements":[{"effect":"allow","action":"order:read"}]}}]}"""

    private fun head(): Long = call("GET", "/documents/tenant:acme").second.get("revision").asLong()

    private fun JsonNode.conflict(): String? = get("conflict")?.asString()

    private fun assertConflict(answer: Pair<Int, JsonNode>, kind: ConflictKind) {
        answer.first.assert().describedAs("${answer.second}").isEqualTo(CONFLICT)
        answer.second.get("code").asString().assert().isEqualTo("CONFLICT")
        answer.second.conflict().assert().describedAs("${answer.second}").isEqualTo(kind.name)
        answer.second.get("detail").asString().assert().isNotBlank()
    }

    @Test
    fun `a 409 names its conflict, and only a 409 does`() {
        ChangeRejectedException.conflict(ConflictKind.BASE_MOVED, "moved").let {
            it.status.assert().isEqualTo(CONFLICT)
            it.conflict.assert().isEqualTo(ConflictKind.BASE_MOVED)
        }
        assertThrownBy<IllegalArgumentException> { ChangeRejectedException(CONFLICT, "a conflict of no kind") }
        assertThrownBy<IllegalArgumentException> {
            ChangeRejectedException(FORBIDDEN, "a refusal", conflict = ConflictKind.STATE)
        }
        ChangeRejectedException.slotHeld(ChangeId("c0"), "held by c0").heldBy.assert().isEqualTo(ChangeId("c0"))
        assertThrownBy<IllegalArgumentException> {
            ChangeRejectedException(CONFLICT, "moved", conflict = ConflictKind.BASE_MOVED, heldBy = ChangeId("c0"))
        }
    }

    @Test
    fun `a proposal on a base that is not the head is BASE_MOVED`() {
        val head = head()
        val stale = call("POST", "/changes", proposal("stale", head - 1))
        assertConflict(stale, ConflictKind.BASE_MOVED)
        stale.second.has("heldBy").assert().isFalse()
    }

    @Test
    fun `a change that wants a held slot is SLOT_HELD naming its holder, and a step its state does not admit is STATE`() {
        val head = head()
        val first = call("POST", "/changes", proposal("first", head)).second.get("id").asString()
        val second = call("POST", "/changes", proposal("second", head)).second.get("id").asString()
        call("POST", "/changes/$first:skip-shadow", """{"reason":"hotfix"}""").first.assert().isEqualTo(OK)
        val held = call("POST", "/changes/$second:skip-shadow", """{"reason":"hotfix"}""")
        assertConflict(held, ConflictKind.SLOT_HELD)
        // The holder, machine-readable, where the detail names it too: nothing more is told than the detail tells.
        held.second.get("heldBy").asString().assert().isEqualTo(first)
        held.second.get("detail").asString().assert().contains(first)
        // A change that holds no slot is not READY: publishing it is not a step its state admits.
        val state = call("POST", "/changes/$second:publish", "{}")
        assertConflict(state, ConflictKind.STATE)
        state.second.has("heldBy").assert().isFalse()
    }

    @Test
    fun `no other status carries a conflict`() {
        val (status, body) = call("POST", "/changes", "{")
        status.assert().isEqualTo(BAD_REQUEST)
        body.conflict().assert().isNull()
    }

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val CONFLICT = 409
    }
}
