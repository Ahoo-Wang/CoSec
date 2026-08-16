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
package me.ahoo.cosec.cache

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.lessThanOrEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class CoCacheTokenStoreTest {

    private val cache = mockk<RevokedTokenCache>(relaxed = true)
    private val tokenStore = CoCacheTokenStore(cache)

    @Test
    fun revoke() {
        val before = Instant.now().epochSecond
        tokenStore.revoke("tokenId", Duration.ofMinutes(10))
        val after = Instant.now().epochSecond

        val ttlAtSlot = slot<Long>()
        verify(exactly = 1) { cache.set("tokenId", capture(ttlAtSlot), true) }
        // ttlAt 单位是秒；+1 是向上取整余量，保证条目不早于 token 自然过期
        assertThat(
            ttlAtSlot.captured,
            allOf(
                greaterThanOrEqualTo(before + 600),
                lessThanOrEqualTo(after + 601)
            )
        )
    }

    @Test
    fun isRevokedWhenTrue() {
        every { cache.get("tokenId") } returns true
        tokenStore.isRevoked("tokenId").assert().isTrue()
    }

    @Test
    fun isRevokedWhenFalse() {
        every { cache.get("tokenId") } returns false
        tokenStore.isRevoked("tokenId").assert().isFalse()
    }

    @Test
    fun isRevokedWhenMiss() {
        every { cache.get("tokenId") } returns null
        tokenStore.isRevoked("tokenId").assert().isFalse()
    }
}
