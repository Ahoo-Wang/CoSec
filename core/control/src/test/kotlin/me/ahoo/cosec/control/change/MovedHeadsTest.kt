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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.control.store.StoredDocument
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** RC1-33: what [MovedHeads] records after a publish, and that recording never undoes it. */
class MovedHeadsTest {
    private val store = InMemoryControlStore()
    private val revisions = Revisions(store)
    private val context = object : ChangeChecks.Context {
        override suspend fun head(key: SnapshotKey): Head? = revisions.head(key)

        override suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<SnapshotKey> =
            revisions.referrers(target, policy)

        override suspend fun apps(): Versioned<AppList>? = store.apps()
    }
    private val moved = MovedHeads(store, ChangeAuthority(PERMIT_ALL), context, Clock.systemUTC())
    private val candidate = StoreFixtures.candidate(ACME)

    private fun checked(checkedWith: Map<SnapshotKey, SnapshotDigest?>) = ChangeRequest(
        id = ChangeId("c1"),
        key = ACME,
        base = Base.NONE,
        operations = emptyList(),
        author = ALICE,
        note = "",
        kind = me.ahoo.cosec.runtime.document.ProvenanceKind.PUBLISH,
        state = ChangeState.PUBLISHED,
        findings = emptyList(),
        candidate = null,
        checkedWith = checkedWith,
        createdAt = StoreFixtures.START,
        updatedAt = StoreFixtures.START,
    )

    @Test
    fun `heads that are as they were checked record nothing`() = runBlocking<Unit> {
        val change = checked(mapOf(SnapshotKey.Platform to null))
        moved.detect(change, change, null, candidate, ALICE_OP).assert().isNull()
    }

    @Test
    fun `a head that moved is recorded with what the analysis finds now`() = runBlocking<Unit> {
        val digest = SnapshotDigest(ByteArray(20) { 1 })
        val change = checked(mapOf(SnapshotKey.Platform to digest, ACME to digest))
        val found = moved.detect(change, change, null, candidate, ALICE_OP)!!
        found.kind.assert().isEqualTo(ChangeEvent.Kind.HEADS_MOVED)
        // The document itself is the publish's own write, never a moved head.
        found.moved.map { it.document }.assert().containsExactly("platform")
        found.moved.single().current.assert().isNull()
    }

    @Test
    fun `an app list that moved is recorded too`() = runBlocking<Unit> {
        val change = checked(emptyMap()).copy(checkedApps = CheckedApps(null))
        store.putApps(null, AppList(setOf("billing"), complete = true)).assert().isTrue()
        val found = moved.detect(change, change, null, candidate, ALICE_OP)!!
        found.moved.single().document.assert().isEqualTo(MovedHeads.APP_LIST)
        // Read as it is now: nothing moved.
        val now = checked(emptyMap()).copy(checkedApps = CheckedApps(store.apps()!!.digest))
        moved.detect(now, now, null, candidate, ALICE_OP).assert().isNull()
    }

    @Test
    fun `a detection that does not finish in time is recorded for review`() = runBlocking<Unit> {
        val slow = object : ControlStore by store {
            override suspend fun head(key: SnapshotKey): StoredDocument? {
                kotlinx.coroutines.delay(1_000)
                return store.head(key)
            }
        }
        val impatient = MovedHeads(slow, ChangeAuthority(PERMIT_ALL), context, Clock.systemUTC(), 50.milliseconds)
        val change = checked(mapOf(SnapshotKey.Platform to null))
        var found: HeadsMoved? = null
        impatient.record(change, change, null, candidate, ALICE_OP) { found = it }
        found!!.kind.assert().isEqualTo(ChangeEvent.Kind.REVIEW_NEEDED)
        found!!.note.assert().contains("could not be compared within")
        // Told apart from a head that moved: nothing is named as moved, and the reason says why.
        found!!.moved.assert().isEmpty()
        found!!.escalations.map { it.reason }.assert().containsExactly(Escalation.DETECTION_TIMED_OUT)
    }

    @Test
    fun `a failure to record is only logged - the publish stands`() = runBlocking<Unit> {
        val change = checked(mapOf(SnapshotKey.Platform to SnapshotDigest(ByteArray(20) { 1 })))
        var asked = false
        moved.record(change, change, null, candidate, ALICE_OP) {
            asked = true
            error("the audit sink is down")
        }
        asked.assert().isTrue()
    }
}
