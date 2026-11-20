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

package me.ahoo.cosec.control.store

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** R2C-1: a message names at most twenty referrers, sorted, and how many there are in all. */
class ReferrerNamesTest {
    private fun tenants(n: Int) = (1..n).map { SnapshotKey.Tenant("t%05d".format(it)) }.toSet()

    @Test
    fun `up to twenty referrers are all named, sorted`() {
        ReferrerNames.of(
            setOf(SnapshotKey.Tenant("b"), SnapshotKey.Tenant("a"))
        ).assert().isEqualTo("[tenant:a, tenant:b]")
        ReferrerNames.of(tenants(20)).assert().endsWith("tenant:t00020]")
    }

    @Test
    fun `more than twenty are the first twenty and the count, whatever their number`() {
        val named = ReferrerNames.of(tenants(50_000))
        named.assert().startsWith("[tenant:t00001, ").endsWith("tenant:t00020] and 49980 more (50000 in all)")
        named.length.assert().isLessThan(400)
        ReferrerNames.of(mapOf(PolicyId("b") to tenants(1), PolicyId("a") to tenants(21))).assert()
            .isEqualTo("[a] by ${ReferrerNames.of(tenants(21))}; [b] by [tenant:t00001]")
    }
}
