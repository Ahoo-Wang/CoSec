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
import me.ahoo.cosec.control.api.ControlPlaneTest.Companion.TENANT
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.engine.ViewVersion
import me.ahoo.cosec.model.BindingId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `CTL-83`: the bound on a document's ended changes may remove the record of the change that published a revision
 * still retained. That revision is explained all the same: `explain` decides on the revision, and the revision names
 * its change and who published it in its own provenance.
 */
class EndedChangeExplainTest {
    private val fixture = ControlPlaneTest()
    private val plane = fixture.plane

    @Test
    fun `a retained revision whose change record is gone is explained from the revision`() = runBlocking<Unit> {
        val alice = fixture.alice
        val change = plane.propose(alice, TENANT, 1, listOf(Operation.RemoveBinding(BindingId("ops"))), "revoke")
        plane.startShadow(alice, change.id)
        fixture.changes.shadowed(change.id)
        plane.publish(alice, change.id)
        val stored = fixture.memory.change(change.id)!!
        fixture.memory.removeChange(TENANT, change.id, stored.digest).assert().isTrue()
        fixture.memory.change(change.id).assert().isNull()

        val explained = plane.explain(alice, fixture.bob("read"), ViewVersion(1, null, 2))

        explained.decision.decision.assert().isEqualTo("Deny")
        explained.version.tenant.assert().isEqualTo(2L)
        val provenance = plane.read(alice, TENANT, 2).provenance!!
        provenance.change.assert().isEqualTo(change.id.value)
        provenance.publishedBy.assert().isEqualTo(stored.value.author)
        plane.explain(
            alice,
            fixture.bob("read"),
            ViewVersion(1, null, 1)
        ).decision.decision.assert().isEqualTo("Permit")
    }
}
