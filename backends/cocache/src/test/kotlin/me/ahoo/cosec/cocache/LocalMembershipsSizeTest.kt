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

package me.ahoo.cosec.cocache

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import javax.management.ObjectName

/**
 * `STA-12`, DR1-D-2: what one entry of [LocalMemberships] retains, by a class histogram of the live heap (which collects
 * first) taken with [MEASURED] and with 2 × [MEASURED] principals held, for a principal of the size its constant is
 * counted at: ten roles in two spaces and five attached policies, ids of a realistic length. Holds
 * [LocalMemberships.ENTRY_BYTES] to it: never below the measurement, and at most twice it.
 */
class LocalMembershipsSizeTest {
    private fun memberships(index: Int) = Memberships(
        Memberships.Source.PROVIDER,
        mapOf(
            SpaceId.DEFAULT to (0 until 6).mapTo(LinkedHashSet()) { RoleId("role-$index-$it") },
            SpaceId("warehouse-$index") to (0 until 4).mapTo(LinkedHashSet()) { RoleId("role-$index-w$it") },
        ),
        (0 until 5).map { PolicyId("policy-$index-$it") },
    )

    private fun liveBytes(): Long {
        val text = ManagementFactory.getPlatformMBeanServer().invoke(
            ObjectName("com.sun.management:type=DiagnosticCommand"),
            "gcClassHistogram",
            arrayOf<Any?>(null),
            arrayOf(Array<String>::class.java.name),
        ) as String
        return text.lines().last { it.trim().startsWith("Total") }.trim().split(Regex("\\s+"))[2].toLong()
    }

    @Test
    fun `the constant the memberships share is counted at is what an entry retains, within a factor of two`() {
        val cache = LocalMemberships(LocalMemberships.DEFAULT_TTL, 4 * MEASURED)
        fun fill(from: Int, until: Int) {
            for (index in from until until) {
                val key = CoCacheMembershipProvider.key(
                    TenantId("tenant-${index % 10}"),
                    PrincipalId("user-$index@example.com")
                )
                cache.get(key) { CompletableFuture.completedFuture(memberships(index)) }.join()
            }
        }
        fill(0, MEASURED)
        val half = liveBytes()
        fill(MEASURED, 2 * MEASURED)
        val all = liveBytes()
        val perEntry = (all - half) / MEASURED
        cache.size.assert().isEqualTo(2 * MEASURED)
        println("LocalMemberships: $perEntry B an entry; the constant is ${LocalMemberships.ENTRY_BYTES} B")
        LocalMemberships.ENTRY_BYTES.assert().isGreaterThanOrEqualTo(perEntry).isLessThanOrEqualTo(perEntry * 2)
    }

    private companion object {
        const val MEASURED = 5_000
    }
}
