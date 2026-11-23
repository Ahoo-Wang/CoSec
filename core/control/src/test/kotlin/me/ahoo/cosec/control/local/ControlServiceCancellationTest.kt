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

package me.ahoo.cosec.control.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlPlaneTest
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * `CTL-82`: a control request has no deadline of its own; its caller's reaches it by cancellation, in process as over
 * the network. A caller that bounds its call with `withTimeout` and runs out before the head write leaves nothing
 * published and the change as it stood, `READY` and holding its own slot: publishing it again publishes it. A caller
 * cancelled after the head write does not stop what follows the write: the change is settled `PUBLISHED`, its slot
 * released and its publish recorded, and the caller stays cancelled.
 */
class ControlServiceCancellationTest {
    private val fixture = ControlPlaneTest()
    private val service = LocalControlService(fixture.plane, IdentityGate())
    private val alice = ControlCaller.of(
        fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin")).securityContext,
    )
    private val acme = SnapshotKey.Tenant("acme")

    @Test
    fun `a caller's timeout before the head write publishes nothing and holds nothing more - CTL-82`() = runBlocking<Unit> {
        val id = ready()
        val revision = fixture.memory.head(acme)!!.document.revision
        val reached = CompletableDeferred<Unit>()
        fixture.beforeWrite = { key, _ ->
            if (key == acme) {
                reached.complete(Unit)
                awaitCancellation()
            }
        }

        assertThrownBy<TimeoutCancellationException> {
            runBlocking { withTimeout(CALLER_DEADLINE) { service.publish(alice, id) } }
        }

        reached.isCompleted.assert().describedAs("the deadline met the publish at its record write").isTrue()
        fixture.memory.head(acme)!!.document.revision.assert().isEqualTo(revision)
        val change = fixture.memory.change(ChangeId(id))!!.value
        change.state.assert().isEqualTo(ChangeState.READY)
        fixture.memory.stage(acme)!!.value.holder.assert().isEqualTo(ChangeId(id))
        fixture.beforeWrite = null
        val published = CoSecJson.mapper.readTree(service.publish(alice, id))
        published.get("state").asString().assert().isEqualTo("PUBLISHED")
        fixture.memory.head(acme)!!.document.revision.assert().isEqualTo(revision + 1)
    }

    @Test
    fun `a caller cancelled after the head write leaves the change settled, released and recorded - CTL-82`() =
        runBlocking<Unit> {
            val id = ready()
            val revision = fixture.memory.head(acme)!!.document.revision
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            // The change's move to PUBLISHED: the first step after the head write.
            fixture.beforeWrite = { _, change ->
                if (change?.id == ChangeId(id) && change.state == ChangeState.PUBLISHED) {
                    reached.complete(Unit)
                    release.await()
                }
            }
            val publishing = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                service.publish(alice, id)
            }
            withTimeout(REACHED_WITHIN) { reached.await() }

            publishing.cancel()
            release.complete(Unit)
            publishing.join()

            publishing.isCancelled.assert().isTrue()
            fixture.memory.head(acme)!!.document.revision.assert().isEqualTo(revision + 1)
            val change = fixture.memory.change(ChangeId(id))!!.value
            change.state.assert().isEqualTo(ChangeState.PUBLISHED)
            change.publishedRevision.assert().isEqualTo(revision + 1)
            fixture.memory.stage(acme)?.value?.holder.assert().isNull()
            fixture.listeners.awaitDelivered()
            fixture.events.filter { it.change == ChangeId(id) }.map { it.kind }.assert()
                .contains(ChangeEvent.Kind.PUBLISHED)
        }

    /** A change of acme's document by alice, `READY` to publish. */
    private suspend fun ready(): String {
        fixture.memory.keepOpenChanges()
        val proposal = """{"document":"tenant:acme","base":${fixture.memory.head(acme)!!.document.revision},""" +
            """"note":"n","operations":[{"op":"putPolicy","policy":{"id":"added","statements":""" +
            """[{"effect":"allow","action":"order:read"}]}}]}"""
        val id = CoSecJson.mapper.readTree(service.propose(alice, proposal)).get("id").asString()
        service.skipShadow(alice, id, "cancellation")
        fixture.memory.change(ChangeId(id))!!.value.state.assert().isEqualTo(ChangeState.READY)
        return id
    }

    private companion object {
        /** The caller's deadline: long enough for an in-memory publish to reach its record write, where it waits. */
        val CALLER_DEADLINE = 2.seconds

        /** How long an in-memory publish may take to reach the step after its head write. */
        val REACHED_WITHIN = 10.seconds
    }
}
