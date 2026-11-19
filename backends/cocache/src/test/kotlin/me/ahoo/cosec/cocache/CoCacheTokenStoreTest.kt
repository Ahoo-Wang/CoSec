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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
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

    /**
     * UA-4: a logout that hands the access token to `TokenStore.revoke` is refused, so it neither revokes nothing in
     * silence nor writes the credential as a key; no id CoSec or another issuer gives a token is refused.
     */
    @Test
    fun `a token given for its id is refused`() = runBlocking<Unit> {
        val tokens = listOf(
            "$HEADER.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl",
            "Bearer $HEADER.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl",
            "bearer $HEADER.eyJzdWIiOiJhbGljZSJ9.",
            "$HEADER.eyJzdWIiOiJhbGljZSJ9.",
            "$HEADER..iv.ciphertext.tag",
        )
        tokens.forEach { token ->
            assertThrownBy<IllegalArgumentException> { runBlocking { tokenStore.revoke(token, Duration.ofMinutes(10)) } }
                .hasMessageContaining("TokenRevoker.revoke(accessToken)")
                .hasMessageNotContaining(HEADER)
        }
        tokenStore.reader.isRevoked("$HEADER.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl").assert().isFalse()
    }

    @Test
    fun `ids are revoked whatever their shape`() = runBlocking<Unit> {
        val ids = listOf(
            "0TfYdOFyAAAx",
            "3f0b3b2e-6c0a-4b8e-9a51-2f0c3d1e5a77",
            "a.b.c",
            "abc.def",
            "${'$'}{x}.y.z",
            "e30.e30.e30.e30",
            "bm90LWpzb24.eyJ9.x",
        )
        ids.forEach { id ->
            tokenStore.revoke(id, Duration.ofMinutes(10))
            tokenStore.reader.isRevoked(id).assert().isTrue()
        }
    }

    private companion object {
        /** `{"alg":"HS256","typ":"JWT"}` */
        const val HEADER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
    }
}
