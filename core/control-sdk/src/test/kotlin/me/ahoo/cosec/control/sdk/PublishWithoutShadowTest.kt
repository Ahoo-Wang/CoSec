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

package me.ahoo.cosec.control.sdk

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * [ControlClient.proposeAndPublishWithoutShadow] over a service that answers as a script says: what it retries, what it
 * waits out, what it answers, and what it leaves behind when it gives up.
 */
class PublishWithoutShadowTest {
    private val acme = SnapshotKey.Tenant("acme")
    private val caller = ControlCaller.service("svc-iam")
    private val role = Operation.PutRole(Role("night-shift", Permissions.Of(listOf("warehouse.read")), app = "wms"))

    /** A service whose every call is logged and answered by [answer]: a change's state, or a refusal thrown. */
    private class Scripted(val answer: (call: String, count: Int) -> String) : ControlService {
        val calls = mutableListOf<String>()

        private fun call(name: String): String {
            calls += name
            return answer(name, calls.count { it == name })
        }

        override suspend fun documents(caller: ControlCaller, kind: DocumentKind, after: String?, limit: Int?) =
            call("documents")

        override suspend fun document(caller: ControlCaller, key: SnapshotKey) = call("document")
        override suspend fun revisions(
            caller: ControlCaller,
            key: SnapshotKey,
            before: Long?,
            limit: Int?
        ) = call("revisions")
        override suspend fun revision(caller: ControlCaller, key: SnapshotKey, revision: Long) = call("revision")
        override suspend fun diff(caller: ControlCaller, key: SnapshotKey, from: Long, to: Long) = call("diff")
        override suspend fun apply(caller: ControlCaller, key: SnapshotKey, request: String) = call("apply")
        override suspend fun propose(caller: ControlCaller, request: String) = call("propose")
        override suspend fun change(caller: ControlCaller, id: String) = call("change")
        override suspend fun changes(
            caller: ControlCaller,
            document: SnapshotKey?,
            state: ChangeState?,
            after: String?,
            limit: Int?,
        ) = call("changes")

        override suspend fun rebase(caller: ControlCaller, id: String) = call("rebase")
        override suspend fun startShadow(caller: ControlCaller, id: String) = call("startShadow")
        override suspend fun skipShadow(caller: ControlCaller, id: String, reason: String) = call("skipShadow")
        override suspend fun abandon(caller: ControlCaller, id: String) = call("abandon")
        override suspend fun publish(caller: ControlCaller, id: String, request: String?) = call("publish")
    }

    private fun run(
        patience: Duration = Duration.ofSeconds(
            5
        ),
        answer: (String, Int) -> String
    ): Pair<Change, List<String>> {
        val service = Scripted(answer)
        val change = runBlocking {
            ControlClient(
                service
            ).proposeAndPublishWithoutShadow(caller, acme, listOf(role), "night shift", "projected", patience)
        }
        return change to service.calls
    }

    private fun refused(service: Scripted, patience: Duration = Duration.ofMillis(200)): ControlRefusedException =
        assertThrownBy<ControlRefusedException> {
            runBlocking {
                ControlClient(
                    service
                ).proposeAndPublishWithoutShadow(caller, acme, listOf(role), "n", "projected", patience)
            }
        }.actual()

    /** The usual answers: the head, then a change that checks, stages and publishes. */
    private fun usual(call: String): String = when (call) {
        "document" -> HEAD
        "propose" -> change("CHECKED")
        "skipShadow" -> change("READY")
        "publish" -> change("PUBLISHED")
        "abandon" -> change("ABANDONED")
        else -> error(call)
    }

    @Test
    fun `it proposes on the head, skips the window and publishes`() {
        val (change, calls) = run { call, _ -> usual(call) }
        change.state.assert().isEqualTo(ChangeState.PUBLISHED)
        calls.assert().containsExactly("document", "propose", "skipShadow", "publish")
    }

    @Test
    fun `a moved head is read again and the change proposed again on it`() {
        val (change, calls) = run { call, count ->
            if (call == "propose" && count == 1) throw conflict(ConflictKind.BASE_MOVED) else usual(call)
        }
        change.state.assert().isEqualTo(ChangeState.PUBLISHED)
        calls.assert().containsExactly("document", "propose", "document", "propose", "skipShadow", "publish")
    }

    @Test
    fun `a held slot is waited out`() {
        val (change, calls) = run { call, count ->
            if (call == "skipShadow" && count <= 2) throw conflict(ConflictKind.SLOT_HELD) else usual(call)
        }
        change.state.assert().isEqualTo(ChangeState.PUBLISHED)
        calls.assert().containsExactly("document", "propose", "skipShadow", "skipShadow", "skipShadow", "publish")
    }

    @Test
    fun `a slot held past its patience abandons the change and throws the refusal`() {
        val service = Scripted { call, _ ->
            if (call == "skipShadow") {
                throw conflict(
                    ConflictKind.SLOT_HELD
                )
            } else {
                usual(call)
            }
        }
        refused(service).conflict.assert().isEqualTo(ConflictKind.SLOT_HELD)
        service.calls.last().assert().isEqualTo("abandon")
        service.calls.assert().doesNotContain("publish")
    }

    @Test
    fun `a head that moved before the publish abandons the change and makes it again`() {
        val (change, calls) = run { call, count ->
            if (call == "publish" && count == 1) throw conflict(ConflictKind.BASE_MOVED) else usual(call)
        }
        change.state.assert().isEqualTo(ChangeState.PUBLISHED)
        calls.assert().containsExactly(
            "document", "propose", "skipShadow", "publish", "abandon",
            "document", "propose", "skipShadow", "publish",
        )
    }

    @Test
    fun `a refusal it does not retry abandons what it proposed and is thrown`() {
        val service = Scripted { call, _ -> if (call == "publish") throw conflict(ConflictKind.STATE) else usual(call) }
        refused(service).conflict.assert().isEqualTo(ConflictKind.STATE)
        service.calls.assert().containsExactly("document", "propose", "skipShadow", "publish", "abandon")
    }

    @Test
    fun `a refused proposal leaves nothing behind`() {
        val service = Scripted { call, _ ->
            if (call == "propose") throw ControlRefusedException(403, """{"code":"FORBIDDEN"}""") else usual(call)
        }
        refused(service).status.assert().isEqualTo(403)
        service.calls.assert().containsExactly("document", "propose")
    }

    @Test
    fun `a check that stops the change answers it abandoned`() {
        val (change, calls) = run { call, _ -> if (call == "propose") change("DRAFT") else usual(call) }
        change.state.assert().isEqualTo(ChangeState.ABANDONED)
        calls.assert().containsExactly("document", "propose", "abandon")
    }

    @Test
    fun `a change that waits for a person is answered as it stands`() {
        val (change, calls) = run { call, _ -> if (call == "skipShadow") change("AWAITING_APPROVAL") else usual(call) }
        change.state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        calls.assert().containsExactly("document", "propose", "skipShadow")
    }

    @Test
    fun `a document not published yet is proposed on revision 0`() {
        var base = -1L
        val (change, _) = run { call, _ ->
            when (call) {
                "document" -> throw ControlRefusedException(404, """{"code":"NOT_FOUND"}""")
                "propose" -> change("CHECKED", base = 0).also { base = 0 }
                else -> usual(call)
            }
        }
        change.state.assert().isEqualTo(ChangeState.PUBLISHED)
        base.assert().isEqualTo(0L)
    }

    @Test
    fun `a refusal reads which conflict it is, and none for another status`() {
        conflict(ConflictKind.SLOT_HELD).conflict.assert().isSameAs(ConflictKind.SLOT_HELD)
        ControlRefusedException(409, """{"conflict":"LATER"}""").conflict?.isUnknown.assert().isEqualTo(true)
        ControlRefusedException(404, """{"code":"NOT_FOUND"}""").conflict.assert().isNull()
    }

    private companion object {
        const val HEAD = """{"schema":2,"revision":3,"publishedAt":"2026-11-21T00:00:00Z"}"""

        fun change(state: String, base: Long = 3) = """{"id":"c1","key":"tenant:acme","kind":"PUBLISH","state":"$state",""" +
            """"base":{"revision":$base},"note":"n","author":{"tenant":"acme","principal":"service:svc-iam"},""" +
            """"operations":[],"findings":[],"createdAt":"2026-11-21T00:00:00Z","updatedAt":"2026-11-21T00:00:01Z"}"""

        fun conflict(kind: ConflictKind) =
            ControlRefusedException(409, """{"code":"CONFLICT","conflict":"${kind.text}"}""")
    }
}
