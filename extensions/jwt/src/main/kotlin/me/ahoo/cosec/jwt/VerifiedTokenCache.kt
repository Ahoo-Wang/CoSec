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
 * - **Bound:** at most [maxBytes], each entry weighed by its token's length times [BYTES_PER_TOKEN_CHAR], what an entry
 *   measurably retains for each character of its token; one over it evicts entries, whichever the map yields first. The
 *   weight is reckoned in [put], on a miss, never in [get]: a hit costs what it cost. An evicted or absent token costs
 *   one verification, never a different answer. The starter's [maxBytes] is the token cache's share of the heap
 *   (`HeapBudget`, `STA-12`).
 * - **Never on refresh, switch or logout:** only the pipeline's authenticator gets a verifier with a cache
 *   ([JwtTokenVerifier.cached]); and the revocation and reserved-attribute checks run after it on every request, hit
 *   or miss (`TokenAuthenticator`).
 */
@InternalCoSecApi
class VerifiedTokenCache(val maxBytes: Long) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive, not $maxBytes." }
    }

    /**
     * A verified token, with the instants its verification compared the clock with, in epoch seconds (java-jwt
     * truncates the clock to whole seconds): valid while `validFrom <= now + skew` and `now < expires`.
     */
    internal class Entry(val token: VerifiedToken, val validFrom: Long, val expires: Long, val weight: Long)

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
    private val heldBytes = AtomicLong()

    /** Requests answered from the cache (`cosec.token.cache.hits`). */
    val hits: Long get() = hitCount.get()

    /** Requests verified because no usable entry was there (`cosec.token.cache.misses`). */
    val misses: Long get() = missCount.get()

    /** Entries removed to keep within [maxBytes] (`cosec.token.cache.evictions`). */
    val evictions: Long get() = evictionCount.get()

    /** The entries held now. */
    val size: Int get() = entries.size

    /** What the entries held now weigh, in bytes. */
    val held: Long get() = heldBytes.get()

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
        if (entry != null && now >= entry.expires && entries.remove(key, entry)) heldBytes.addAndGet(-entry.weight)
        missCount.incrementAndGet()
        return null
    }

    /** Keeps [entry], whose weight [weigh] reckoned on this miss, and evicts others while the cache is over [maxBytes]. */
    internal fun put(key: Key, entry: Entry) {
        val previous = entries.put(key, entry)
        heldBytes.addAndGet(entry.weight - (previous?.weight ?: 0))
        val victims = entries.entries.iterator()
        while (heldBytes.get() > maxBytes && victims.hasNext()) {
            val victim = victims.next()
            if (victim.key != key && entries.remove(victim.key, victim.value)) {
                heldBytes.addAndGet(-victim.value.weight)
                evictionCount.incrementAndGet()
            }
        }
    }

    @InternalCoSecApi
    companion object {
        /**
         * What an entry retains for each character of its token: its key, the map's node, the [VerifiedToken] and its
         * claims, measured with JOL (`VerifiedTokenCacheSizeTest` holds it, never below the larger of a large and a
         * small token's measurement, and at most twice it).
         */
        const val BYTES_PER_TOKEN_CHAR: Long = 5

        /** What an entry of [token] weighs: reckoned once, as it is put. */
        fun weigh(token: String): Long = token.length * BYTES_PER_TOKEN_CHAR

        private val SHA_256: ThreadLocal<MessageDigest> = ThreadLocal.withInitial { digest() }

        private fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")
    }
}
