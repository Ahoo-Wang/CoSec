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

package me.ahoo.cosec.cocache.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.scope.ScopeResolver
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
            "tenant:a\nb", "tenant:" + "t".repeat(129), "app:*", "app: ", "app:a\nb", "app:" + "a".repeat(129),
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

    /**
     * SCP-10, R1-6 of #1194's review: an app key takes only an app id a request could declare, so the control plane
     * takes none the data plane could never read; a refused id is not echoed raw.
     */
    @Test
    fun anAppIdIsTheIdARequestCouldDeclare() {
        listOf("a:b", "*", "", " ", "\t", "a\nb", "a\u0000b", "a\u007Fb", "a\u0085b", "a".repeat(129)).forEach { id ->
            SnapshotKey.App.isValidAppId(id).assert().describedAs(id).isFalse()
            ScopeResolver.isValidAppId(id).assert().describedAs(id).isFalse()
            assertThrownBy<IllegalArgumentException> { SnapshotKey.App(id) }
        }
        assertThrownBy<IllegalArgumentException> { SnapshotKey.App("a\nforged") }
            .hasMessageContaining("a\\u000Aforged").hasMessageNotContaining("\n")
        listOf("order", "order app", "ｏrder", "a*b", "a".repeat(128)).forEach { id ->
            SnapshotKeyEncoder.decode(
                SnapshotKeyEncoder.encode(SnapshotKey.App(id))
            ).assert().isEqualTo(SnapshotKey.App(id))
        }
    }
}
