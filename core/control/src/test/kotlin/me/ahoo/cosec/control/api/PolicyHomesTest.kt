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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class)

package me.ahoo.cosec.control.api

import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.BuiltinPolicies
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** `CTL-51`: the document that defines a policy, and nothing is guessed when none does. */
class PolicyHomesTest {
    private fun held(key: SnapshotKey, vararg ids: String) =
        Held(StoreFixtures.candidate(key, ids.map { allow(it) }), null)

    private val homes = PolicyHomes(
        HeldDocuments(
            held(SnapshotKey.Platform, "platform-base"),
            held(SnapshotKey.App("order"), "perm:order/read"),
            held(SnapshotKey.Tenant("acme"), "own"),
        )
    )

    @Test
    fun `a policy is the document's that defines it, the binding's own first, then the platform's, then the app's`() {
        homes.of(DocumentKind.TENANT, PolicyId("own")).assert().isEqualTo(DocumentKind.TENANT)
        homes.of(DocumentKind.TENANT, PolicyId("platform-base")).assert().isEqualTo(DocumentKind.PLATFORM)
        homes.of(DocumentKind.TENANT, PolicyId("perm:order/read")).assert().isEqualTo(DocumentKind.APP)
        homes.of(DocumentKind.APP, PolicyId("perm:order/read")).assert().isEqualTo(DocumentKind.APP)
    }

    @Test
    fun `a built-in policy stands for the binding's document`() {
        homes.of(DocumentKind.PLATFORM, BuiltinPolicies.ROOT).assert().isEqualTo(DocumentKind.PLATFORM)
        homes.of(DocumentKind.TENANT, BuiltinPolicies.TENANT_ADMIN).assert().isEqualTo(DocumentKind.TENANT)
    }

    @Test
    fun `a policy no held document defines and no built-in is has no home, so no statement is shown`() {
        homes.of(DocumentKind.TENANT, PolicyId("elsewhere")).assert().isNull()
        PolicyHomes(HeldDocuments(null, null, null)).of(DocumentKind.TENANT, PolicyId("own")).assert().isNull()
    }
}
