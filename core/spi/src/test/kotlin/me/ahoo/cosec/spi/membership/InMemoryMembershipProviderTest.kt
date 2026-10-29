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

package me.ahoo.cosec.spi.membership

import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.now
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InMemoryMembershipProviderTest {
    private val provider = InMemoryMembershipProvider()
    private val tenant = TenantId("tenant")
    private val alice = PrincipalId("alice")

    private fun memberships(role: String) =
        Memberships(Memberships.Source.PROVIDER, mapOf(SpaceId.DEFAULT to setOf(RoleId(role))), emptyList())

    @Test
    fun putReplaceAndRemove() {
        now { provider.memberships(tenant, alice) }.assert().isSameAs(MembershipAnswer.Unknown)

        val admin = memberships("admin")
        provider.put(tenant, alice, admin)
        (now { provider.memberships(tenant, alice) } as MembershipAnswer.Known).memberships.assert().isSameAs(admin)
        now { provider.memberships(TenantId("other"), alice) }.assert().isSameAs(MembershipAnswer.Unknown)

        val viewer = memberships("viewer")
        provider.put(tenant, alice, viewer)
        (now { provider.memberships(tenant, alice) } as MembershipAnswer.Known).memberships.assert().isSameAs(viewer)

        provider.remove(tenant, alice)
        now { provider.memberships(tenant, alice) }.assert().isSameAs(MembershipAnswer.Unknown)
    }

    @Test
    fun claimedMembershipsAreRefused() {
        assertThrows<IllegalArgumentException> {
            provider.put(tenant, alice, Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList()))
        }
    }

    @Test
    fun concurrentChangesAndLookups() {
        val principals = (0 until PRINCIPALS).map { PrincipalId("p$it") }
        val executor = Executors.newFixedThreadPool(THREADS)
        val start = CountDownLatch(1)
        try {
            val tasks = (0 until THREADS).map { thread ->
                executor.submit {
                    start.await()
                    repeat(ROUNDS) { round ->
                        val principal = principals[(thread + round) % PRINCIPALS]
                        provider.put(tenant, principal, memberships("r$thread"))
                        // whatever another thread wrote, a lookup sees a complete value from the provider
                        (now { provider.memberships(tenant, principal) } as MembershipAnswer.Known).memberships.source
                            .assert().isEqualTo(Memberships.Source.PROVIDER)
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        principals.forEach { principal ->
            (
                now {
                    provider.memberships(
                        tenant,
                        principal
                    )
                } as MembershipAnswer.Known
                ).memberships.roles[SpaceId.DEFAULT].assert().hasSize(1)
        }
    }

    companion object {
        private const val THREADS = 8
        private const val PRINCIPALS = 4
        private const val ROUNDS = 1_000
    }
}
