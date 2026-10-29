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

package me.ahoo.cosec.cache.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class SnapshotKeyEncoderTest {
    @Test
    fun encodesExplicitly() {
        SnapshotKeyEncoder.encode(SnapshotKey.App("order-service")).assert().isEqualTo("app:order-service")
        SnapshotKeyEncoder.encode(SnapshotKey.Platform).assert().isEqualTo("platform")
        SnapshotKeyEncoder.encode(SnapshotKey.Tenant("acme")).assert().isEqualTo("tenant:acme")
        SnapshotKeyEncoder.encode(SnapshotKey.Tenant("(platform)")).assert().isEqualTo("tenant:(platform)")
    }

    @Test
    fun decodesWhatItEncodes() {
        listOf(
            SnapshotKey.App("order-service"),
            SnapshotKey.Platform,
            SnapshotKey.Tenant("acme"),
            SnapshotKey.Tenant("(0)"),
        ).forEach {
            SnapshotKeyEncoder.decode(SnapshotKeyEncoder.encode(it)).assert().isEqualTo(it)
        }
    }

    @Test
    fun decodesNothingElse() {
        listOf(
            "", "policy", "policies", "app:", "app:a:b", "other:x", "platform:x", "tenant:", "tenant:a:b", "tenant:*", "tenant: ",
            "tenant:a\nb", "tenant:" + "t".repeat(129),
        ).forEach {
            SnapshotKeyEncoder.decode(it).assert().describedAs(it).isNull()
        }
    }

    /** A tenant id names exactly one key: no separator, wildcard, blank, control character or unbounded length. */
    @Test
    fun aTenantIdCannotReachAnotherKey() {
        listOf("a:b", "*", "", " ", "a\nb", "a\u0000b", "t".repeat(129)).forEach { id ->
            SnapshotKey.Tenant.isValidTenantId(id).assert().describedAs(id).isFalse()
            assertThrownBy<IllegalArgumentException> { SnapshotKey.Tenant(id) }
        }
        listOf("acme", "(platform)", "(0)", "a*b", "t".repeat(128)).forEach { id ->
            SnapshotKeyEncoder.decode(
                SnapshotKeyEncoder.encode(SnapshotKey.Tenant(id))
            ).assert().isEqualTo(SnapshotKey.Tenant(id))
        }
    }
}
