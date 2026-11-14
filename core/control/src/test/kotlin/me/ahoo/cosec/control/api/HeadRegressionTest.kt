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
import me.ahoo.cosec.control.change.ACME
import me.ahoo.cosec.control.change.ChangeAuthority
import me.ahoo.cosec.control.change.ChangeHarness
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.PERMIT_ALL
import me.ahoo.cosec.control.change.PLATO
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.flat.ViewVersion
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * A head that moved back (a partial loss of data: a shard's failover in a Redis Cluster, a partial restore) leaves
 * entries above it in the revision index, of revisions no longer on its chain. Nothing reads them: the history, a
 * revision by number, a diff, a rollback target and `explain`'s `at` all answer as for a revision not kept (410).
 */
class HeadRegressionTest {
    private val harness = ChangeHarness()
    private val memory = harness.memory
    private val revisions = harness.revisions
    private val plane = DefaultControlPlane(
        harness.changes,
        revisions,
        ChangeAuthority(PERMIT_ALL, ChangeOptions(shadow = IMMEDIATE_WINDOW)),
        Simulator(),
    )

    private suspend fun publish(policy: String): SnapshotDigest {
        val published = revisions.publish(
            ACME,
            revisions.head(ACME),
            StoreFixtures.candidate(ACME, listOf(allow(policy))),
            StoreFixtures.publication(),
        )
        return (published as PublishOutcome.Published).digest
    }

    /** Revisions 1 to 4, then the head back at 1, its index and records as they were. */
    private suspend fun movedBack() {
        val first = publish("r1")
        publish("r2")
        publish("r3")
        publish("r4")
        val head = memory.head(ACME)!!
        memory.putHead(ACME, head.digest, memory.record(ACME, first)!!)
        memory.index(ACME)!!.value.entries.map { it.revision }.assert().containsExactly(1L, 2L, 3L, 4L)
    }

    private suspend fun status(block: suspend () -> Unit): Int = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e.status
    }

    private fun explained(at: Long) = SimulatedRequest(
        tenant = TenantId("acme"),
        principal = "bob",
        action = Action("order", "read"),
        resource = Resource("order", "1", emptyMap()),
        time = StoreFixtures.START.toEpochMilli(),
    ) to ViewVersion(0, null, at)

    private suspend fun assertNotRead(revision: Long) {
        revisions.history(ACME).map { it.revision }.assert().doesNotContain(revision)
        revisions.revision(ACME, revision).assert().isNull()
        plane.history(PLATO, ACME, null, 10).map { it.revision }.assert().doesNotContain(revision)
        status { plane.read(PLATO, ACME, revision) }.assert().isEqualTo(GONE)
        status { plane.diff(PLATO, ACME, 1, revision) }.assert().isEqualTo(GONE)
        status { plane.rollback(PLATO, ACME, revision, "back") }.assert().isEqualTo(GONE)
        val (request, at) = explained(revision)
        status { plane.explain(PLATO, request, at) }.assert().isEqualTo(GONE)
    }

    @Test
    fun `a revision above a head that moved back is read by nothing`() = runBlocking<Unit> {
        movedBack()
        revisions.history(ACME).map { it.revision }.assert().containsExactly(1L)
        for (revision in listOf(2L, 3L, 4L)) {
            assertNotRead(revision)
        }
        plane.read(PLATO, ACME, 1).policies.map { it.id.value }.assert().containsExactly("r1")
    }

    @Test
    fun `publishing over a head that moved back reads as the new chain, a number at a time`() = runBlocking<Unit> {
        movedBack()
        publish("again2")
        revisions.history(ACME).map { it.revision }.assert().containsExactly(1L, 2L)
        plane.read(PLATO, ACME, 2).policies.map { it.id.value }.assert().containsExactly("again2")
        assertNotRead(3)
        assertNotRead(4)

        publish("again3")
        revisions.history(ACME).map { it.revision }.assert().containsExactly(1L, 2L, 3L)
        plane.read(PLATO, ACME, 3).policies.map { it.id.value }.assert().containsExactly("again3")
        assertNotRead(4)
    }

    private companion object {
        const val GONE = 410
    }
}
