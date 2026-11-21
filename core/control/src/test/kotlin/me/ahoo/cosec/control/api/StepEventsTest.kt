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
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `CTL-63`: a step that changes a change's record writes a change event when it does. Skipping a shadow window is a
 * privileged action (`cosec:skip-shadow`): recorded when it is skipped, with its reason, not only in the provenance of
 * a revision the change may never publish; a rebase is recorded too. A refused step changes nothing and records nothing.
 */
class StepEventsTest {
    private val fixture = ControlPlaneTest()
    private val acme = SnapshotKey.Tenant("acme")

    private suspend fun kinds(id: ChangeId): List<ChangeEvent.Kind> {
        fixture.listeners.awaitDelivered()
        return fixture.events.filter { it.change == id }.map { it.kind }
    }

    private suspend fun proposed(note: String, base: Long = 1) =
        fixture.changes.propose(fixture.alice, acme, base, listOf(Operation.PutPolicy(allow(note))), note)

    @Test
    fun `a skipped window is recorded when it is skipped, with its reason, whether or not the change publishes - CTL-63`() =
        runBlocking<Unit> {
            val change = proposed("skip")
            fixture.changes.skipShadow(fixture.alice, change.id, "hotfix for incident 42")
            fixture.changes.abandon(fixture.alice, change.id)
            kinds(change.id).assert()
                .containsExactly(ChangeEvent.Kind.CHECKED, ChangeEvent.Kind.SHADOW_SKIPPED, ChangeEvent.Kind.ABANDONED)
            val skipped = fixture.events.single { it.change == change.id && it.kind == ChangeEvent.Kind.SHADOW_SKIPPED }
            skipped.note.assert().contains("hotfix for incident 42")
            skipped.operator.assert().isEqualTo(fixture.alice.actor)
        }

    @Test
    fun `a rebase is recorded when it rebases - CTL-63`() = runBlocking<Unit> {
        val behind = proposed("behind")
        val ahead = proposed("ahead")
        fixture.changes.skipShadow(fixture.alice, ahead.id, "move the head")
        fixture.changes.publish(fixture.alice, ahead.id)
        fixture.changes.rebase(fixture.alice, behind.id).base.revision.assert().isEqualTo(2L)
        kinds(behind.id).assert().containsExactly(ChangeEvent.Kind.CHECKED, ChangeEvent.Kind.REBASED)
    }

    @Test
    fun `a refused skip records nothing - CTL-63`() = runBlocking<Unit> {
        val change = proposed("refused")
        val viewer = fixture.engine.operator(ControlPlaneTest.ACME, "vera", roles = listOf("viewer"))
        runCatching { fixture.changes.skipShadow(viewer, change.id, "no right") }.exceptionOrNull()
            .assert().isInstanceOf(ChangeRejectedException::class.java)
        kinds(change.id).assert().containsExactly(ChangeEvent.Kind.CHECKED)
    }
}
