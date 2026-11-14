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

package me.ahoo.cosec.spi.token

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

class InMemoryTokenFamilyStoreTest {
    private val login = Instant.parse("2026-11-01T00:00:00Z")
    private var now = login
    private val store = InMemoryTokenFamilyStore { now }

    private fun family(id: String = "f", generation: Long = 1) = TokenFamily(
        id,
        TenantId("t"),
        PrincipalId("alice"),
        login,
        login.plus(Duration.ofDays(30)),
        generation,
        null,
        false
    )

    @Test
    fun `a family that does not hold is refused`() {
        assertThrows<IllegalArgumentException> { family(id = "") }
        assertThrows<IllegalArgumentException> { family(generation = 0) }
        assertThrows<IllegalArgumentException> {
            TokenFamily("f", TenantId("t"), PrincipalId("alice"), login, login, 1, null, false)
        }
    }

    @Test
    fun `families are values that rotate and revoke`() {
        val rotated = family().rotated(login.plusSeconds(5))
        rotated.generation.assert().isEqualTo(2)
        rotated.rotatedAt.assert().isEqualTo(login.plusSeconds(5))
        rotated.revoked().revoked.assert().isTrue()
        rotated.revoked().generation.assert().isEqualTo(2)
        family().assert().isEqualTo(family()).hasSameHashCodeAs(family()).isNotEqualTo(rotated).isNotEqualTo("f")
        family().toString().assert().isEqualTo("TokenFamily(f, generation 1, revoked=false)")
    }

    @Test
    fun `a family is created once while it lives, and again once it ended`() = runTest {
        store.create(family()).assert().isTrue()
        store.create(family()).assert().isFalse()
        now = login.plus(Duration.ofDays(30))
        store.read("f").assert().isNull()
        store.create(family()).assert().isTrue()
    }

    /** Of two writes over one version at most one lands, and nothing replaces a family that ended or never was. */
    @Test
    fun `a replace lands only over the version read, while the family lives`() = runTest {
        store.replace("1", family()).assert().isFalse()
        store.create(family())
        val read = store.read("f")!!
        store.replace(read.version, read.family.rotated(now)).assert().isTrue()
        store.replace(read.version, read.family.rotated(now)).assert().isFalse()
        store.read("f")!!.family.generation.assert().isEqualTo(2)

        val current = store.read("f")!!
        now = login.plus(Duration.ofDays(30))
        store.replace(current.version, current.family.revoked()).assert().isFalse()
    }
}
