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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/** [CoCacheTokenStore] over CoCache's in-memory backend; the entry's lifetime is in `CoCacheTokenStoreIntegrationTest`. */
class CoCacheTokenStoreTest {
    private val caches = CoSecTestCaches()
    private val tokenStore = CoCacheTokenStore(caches.revokedTokenCache)

    @AfterEach
    fun close() {
        caches.close()
    }

    @Test
    fun revoke() = runBlocking<Unit> {
        tokenStore.revoke("tokenId", Duration.ofMinutes(10))

        tokenStore.reader.isRevoked("tokenId").assert().isTrue()
        tokenStore.reader.isRevoked("other").assert().isFalse()
    }

    /** One extra second: a token already past its lifetime is still revoked when the call returns. */
    @Test
    fun revokeExpiredToken() = runBlocking<Unit> {
        tokenStore.revoke("tokenId", Duration.ofSeconds(-5))

        tokenStore.reader.isRevoked("tokenId").assert().isTrue()
    }
}
