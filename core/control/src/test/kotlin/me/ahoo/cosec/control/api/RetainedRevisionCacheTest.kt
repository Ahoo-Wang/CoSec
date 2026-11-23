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
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.model.BindingId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** DR1-F-4: a retained revision is cached by its digest, like a head, so explaining at one again compiles nothing. */
class RetainedRevisionCacheTest {
    @Test
    fun `explaining again at a retained revision compiles nothing again`() = runBlocking<Unit> {
        val fixture = ControlPlaneTest()
        val change = fixture.plane.propose(
            fixture.alice,
            ControlPlaneTest.TENANT,
            1,
            listOf(Operation.RemoveBinding(BindingId("ops"))),
            "revoke",
        )
        fixture.plane.startShadow(fixture.alice, change.id)
        fixture.changes.shadowed(change.id)
        fixture.plane.publish(fixture.alice, change.id)
        val then = me.ahoo.cosec.engine.ViewVersion(1, null, 1)
        fixture.plane.explain(fixture.alice, fixture.bob("read"), then).decision.decision.assert().isEqualTo("Permit")
        val first = fixture.simulator.compilations.get()
        repeat(5) { fixture.plane.explain(fixture.alice, fixture.bob("read"), then) }
        fixture.simulator.compilations.get().assert().isEqualTo(first)
    }
}
