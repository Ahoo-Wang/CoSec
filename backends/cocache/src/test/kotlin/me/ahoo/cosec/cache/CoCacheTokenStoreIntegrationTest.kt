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

import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * CoCacheTokenStore against real Redis, with two instances sharing it.
 */
internal class CoCacheTokenStoreIntegrationTest {
    private val backend = CoSecTestCaches.redisBackend()
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()
    private val instanceA = CoSecTestCaches(backend, keyPrefix)
    private val instanceB = CoSecTestCaches(backend, keyPrefix)
    private val tokenStoreA = CoCacheTokenStore(instanceA.revokedTokenCache)
    private val tokenStoreB = CoCacheTokenStore(instanceB.revokedTokenCache)

    @AfterEach
    fun destroy() {
        instanceA.close()
        instanceB.close()
        backend.close()
    }

    @Test
    fun revokeThenIsRevokedUntilTtlExpires() {
        val tokenId = RandomIdGenerator.generate()
        tokenStoreA.isRevoked(tokenId).assert().isFalse()
        tokenStoreA.revoke(tokenId, REVOCATION_TTL)
        tokenStoreA.isRevoked(tokenId).assert().isTrue()
        // the entry lives for the ttl plus one second
        Thread.sleep(REVOCATION_TTL.toMillis() + MARGIN_MS)
        tokenStoreA.isRevoked(tokenId).assert().isFalse()
    }

    @Test
    fun isRevokedWhenUnknownTokenId() {
        tokenStoreA.isRevoked(RandomIdGenerator.generate()).assert().isFalse()
    }

    @Test
    fun revocationOnOneInstanceReplacesCachedNotRevokedOnAnother() {
        val tokenId = RandomIdGenerator.generate()
        // instance B caches "not revoked" locally
        tokenStoreB.isRevoked(tokenId).assert().isFalse()

        tokenStoreA.revoke(tokenId, Duration.ofMinutes(1))

        // the write's invalidation reaches B well before the local "not revoked" answer would expire
        val deadline = System.nanoTime() + Duration.ofSeconds(RevokedTokenCache.MISSING_TTL_SECONDS / 2).toNanos()
        while (!tokenStoreB.isRevoked(tokenId) && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MS)
        }
        tokenStoreB.isRevoked(tokenId).assert().isTrue()
    }

    companion object {
        private val REVOCATION_TTL = Duration.ofSeconds(1)
        private const val MARGIN_MS = 2_000L
        private const val POLL_MS = 50L
    }
}
