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
import me.ahoo.cosec.spi.token.VerifiedToken
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The tokens CoSec's own verifier verified, kept so a token seen again is not verified again (R2B-22, `IDN-32`): memory
 * for the signature check and the claims' reading on every request of the gateway.
 *
 * A cached verification is what verifying the token again would answer. [JwtTokenVerifier.verify] is a function of the
 * token, of what is fixed for the verifier's life (its key, its issuer skew) and of its clock alone; an entry keeps the
 * instants the clock is compared with (`exp`, and the later of `iat` and `nbf`), and answers only while the
 * verifier's own clock is where verifying again would succeed. Anywhere else the verifier verifies again, and answers
 * exactly what it would without a cache (expired, not valid yet).
 *
 * - **Key:** SHA-256 of the whole token as `verify` reads it: no raw token is kept, and a token that differs in any
 *   byte, its signature included, is another key.
 * - **Value:** the immutable [VerifiedToken]; only a successful verification is kept.
 * - **Bound:** at most [maxEntries] entries; one more evicts one, whichever the map yields first. An evicted or absent
 *   token costs one verification, never a different answer.
 * - **Never on refresh, switch or logout:** only the pipeline's authenticator gets a verifier with a cache
 *   ([JwtTokenVerifier.cached]); and the revocation and reserved-attribute checks run after it on every request, hit
 *   or miss (`TokenAuthenticator`).
 */
@InternalCoSecApi
class VerifiedTokenCache(val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, not $maxEntries." }
    }

    /**
     * A verified token, with the instants its verification compared the clock with, in epoch seconds (java-jwt
     * truncates the clock to whole seconds): valid while `validFrom <= now + skew` and `now < expires`.
     */
    internal class Entry(val token: VerifiedToken, val validFrom: Long, val expires: Long)

    /** A token's SHA-256, as four longs: equality and a hash without an array. */
    internal class Key(private val a: Long, private val b: Long, private val c: Long, private val d: Long) {
        override fun equals(other: Any?): Boolean =
            other is Key && a == other.a && b == other.b && c == other.c && d == other.d

        override fun hashCode(): Int = a.toInt()
    }

    private val entries = ConcurrentHashMap<Key, Entry>()

    private val hitCount = AtomicLong()
    private val missCount = AtomicLong()
    private val evictionCount = AtomicLong()

    /** Requests answered from the cache (`cosec.token.cache.hits`). */
    val hits: Long get() = hitCount.get()

    /** Requests verified because no usable entry was there (`cosec.token.cache.misses`). */
    val misses: Long get() = missCount.get()

    /** Entries removed to keep within [maxEntries] (`cosec.token.cache.evictions`). */
    val evictions: Long get() = evictionCount.get()

    /** The entries held now. */
    val size: Int get() = entries.size

    internal fun key(token: String): Key {
        val digest = SHA_256.get().digest(token.toByteArray(Charsets.UTF_8))
        val bytes = ByteBuffer.wrap(digest)
        return Key(bytes.getLong(), bytes.getLong(), bytes.getLong(), bytes.getLong())
    }

    /**
     * The token of [key] when its entry is valid at [now] (epoch seconds) with [skewSeconds]; null otherwise, and then
     * an entry past its expiry is dropped.
     */
    internal fun get(key: Key, now: Long, skewSeconds: Long): VerifiedToken? {
        val entry = entries[key]
        if (entry != null && entry.validFrom <= now + skewSeconds && now < entry.expires) {
            hitCount.incrementAndGet()
            return entry.token
        }
        if (entry != null && now >= entry.expires) entries.remove(key, entry)
        missCount.incrementAndGet()
        return null
    }

    internal fun put(key: Key, entry: Entry) {
        if (entries.size >= maxEntries && !entries.containsKey(key)) {
            val victim = entries.keys.iterator()
            if (victim.hasNext() && entries.remove(victim.next()) != null) evictionCount.incrementAndGet()
        }
        entries[key] = entry
    }

    @InternalCoSecApi
    companion object {
        /** See `wiki/operations/configuration.md` for how it is sized. */
        const val DEFAULT_MAX_ENTRIES = 16_384

        private val SHA_256: ThreadLocal<MessageDigest> = ThreadLocal.withInitial { digest() }

        private fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")
    }
}
