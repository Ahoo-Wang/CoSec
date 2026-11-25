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

package me.ahoo.cosec.jwt

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

/** The verified-token cache's key: the SHA-256 of a token's UTF-8 bytes, whichever thread makes it (`IDN-32`). */
class VerifiedTokenCacheKeyTest {
    private val cache = VerifiedTokenCache(Long.MAX_VALUE)

    private fun reference(token: String): VerifiedTokenCache.Key {
        val bytes = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
        return VerifiedTokenCache.Key(bytes.getLong(), bytes.getLong(), bytes.getLong(), bytes.getLong())
    }

    @Test
    fun `a token's key is the SHA-256 of its UTF-8 bytes, ASCII or not, however long`() {
        val tokens = listOf("", "a", "eyJhbGciOiJIUzI1NiJ9.e30.sig", "x".repeat(255), "y".repeat(256), "z".repeat(257)) +
            listOf("q".repeat(1_000), "ascii-then-é", "é" + "a".repeat(300), "a".repeat(300) + "😀")
        tokens.forEach { cache.key(it).assert().describedAs(it.take(20)).isEqualTo(reference(it)) }
        cache.key("a.b.c").assert().isNotEqualTo(cache.key("a.b.d"))
    }

    /** The buffers are each thread's own: threads that make keys at once each get their token's. */
    @Test
    fun `keys made at once on many threads are each their own token's`() {
        val tokens = (0 until THREADS).map { "token-$it." + "p".repeat(it * 97) + ".sig-$it" }
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(THREADS)
        try {
            val results = tokens.map { token ->
                pool.submit<Boolean> {
                    start.await()
                    (0 until ROUNDS).all { cache.key(token) == reference(token) }
                }
            }
            start.countDown()
            results.forEach { it.get().assert().isTrue() }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val THREADS = 8
        const val ROUNDS = 2_000
    }
}
