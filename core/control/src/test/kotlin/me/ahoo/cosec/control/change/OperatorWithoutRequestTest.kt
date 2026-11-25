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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `IDN-44`: an operator without the request it came in through says nothing of its credential, and the control plane
 * reads it as the less: a workload's, with no principal bindings and no break-glass, so never the trust root. No
 * production path builds one that reaches these readings (`Operator`); they must not depend on that staying true.
 */
class OperatorWithoutRequestTest {
    private val unrequested = Operator(Actor(TenantId.PLATFORM, PrincipalId("plato")), NO_MEMBERSHIPS)

    @Test
    fun `an operator without a request has no principal bindings and no break-glass - IDN-44`() {
        unrequested.principalBindings.assert().isFalse()
        unrequested.breakGlass.assert().isFalse()
        // The same principal, as a person signed in with CoSec's own token, has both.
        PLATO.principalBindings.assert().isTrue()
        PLATO.breakGlass.assert().isTrue()
    }

    @Test
    fun `an operator without a request is a workload's, and never the trust root - IDN-44`() = runBlocking<Unit> {
        val authority = ChangeAuthority(PERMIT_ALL, revocations = RevocationReader.NONE)
        authority.approvals.workload(unrequested).assert().isTrue()
        authority.approvals.workload(PLATO).assert().isFalse()
        val changes = ChangeHarness(
            options = me.ahoo.cosec.control.lifecycle.ChangeOptions(shadow = IMMEDIATE_WINDOW)
        ).changes
        val operations = listOf(Operation.PutPolicy(allow("p")))
        // A platform person holding cosec:approve is the trust root: the platform change needs no one else.
        changes.propose(PLATO, SnapshotKey.Platform, 0, operations, "by a person").escalations.assert().isEmpty()
        // Without a request, the same principal is no one's trust root: what it changes waits for a person.
        changes.propose(unrequested, SnapshotKey.Platform, 0, operations, "without a request").escalations
            .assert().isNotEmpty()
    }
}
