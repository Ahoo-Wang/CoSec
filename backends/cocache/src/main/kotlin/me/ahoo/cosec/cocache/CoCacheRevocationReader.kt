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

import kotlinx.coroutines.future.await
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The [RevocationReader] over the caches [CoCacheTokenStore] writes: the non-blocking reads the coroutine pipeline makes
 * (RFC 0006 §5.3.2), and the reads refresh and logout make through the same judgement.
 *
 * Both reads go through CoCache's async view. A local hit answers on the caller's thread without suspending (RFC 0006
 * T1); a miss reads Redis without blocking any thread and completes on CoCache's async pool, from which the pipeline
 * moves on (T2). A Redis read with no answer (`Lookup.Unknown`: Redis down, a command that timed out) is
 * [RevocationUnavailableException], never "not revoked": the tables read a failure as unknown ([CoSecCacheSpecs.locks]),
 * and the caller decides what it means. Content the tables cannot decode is an error of its own, never an unavailable
 * store. An id that names no key (an unpaired surrogate, [RevokedTokenCache.key], RC1-52) throws before Redis is asked.
 *
 * Each read that goes to Redis has [readTimeout] (R2D-8): a Redis that hangs is unavailable once it is spent, as one that
 * refuses the connection is at once, so the pipeline's verification answers within the authenticator's own budget, and
 * a refresh fails rather than waits. The command itself stays pending until the store's command timeout. A local hit,
 * a future already complete, is not timed: no timer, no allocation.
 */
internal class CoCacheRevocationReader @JvmOverloads constructor(
    private val cache: RevokedTokenCache,
    private val subjectCache: RevokedSubjectCache? = null,
    private val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
) : RevocationReader {
    init {
        require(!readTimeout.isNegative && !readTimeout.isZero) { "readTimeout must be positive, not $readTimeout." }
    }

    private val timeoutNanos = readTimeout.toNanos()

    override suspend fun isRevoked(tokenId: String): Boolean = read(cache, RevokedTokenCache.key(tokenId)) == true

    /** Null without [subjectCache]: then no principal's tokens can have been revoked through this store. */
    override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? {
        val subjectCache = subjectCache ?: return null
        return read(subjectCache, RevokedSubjectCache.key(tenant, principal))?.let(Instant::ofEpochMilli)
    }

    private suspend fun <V : Any> read(store: Store<String, V>, key: String): V? {
        val future = store.async().lookup(key)
        // A copy, timed: CoCache may hand the same future to concurrent readers of a key, whom a timeout must not fail.
        val answer = if (future.isDone) future else future.copy().orTimeout(timeoutNanos, TimeUnit.NANOSECONDS)
        val lookup = try {
            answer.await()
        } catch (timeout: TimeoutException) {
            throw RevocationUnavailableException("The revocation store did not answer within $readTimeout.", timeout)
        }
        return when (lookup) {
            is Lookup.Present -> lookup.value
            is Lookup.Absent -> null
            is Lookup.Unknown -> throw unanswered(lookup.error)
        }
    }

    private fun unanswered(error: CacheReadException): Exception =
        if (generateSequence<Throwable>(error) { it.cause }.any { it is ForeignFormatException }) {
            error
        } else {
            RevocationUnavailableException("The revocation store could not be read: ${error.message}", error)
        }

    companion object {
        /**
         * Each read's budget: two of them, one after the other, end well within the pipeline's authentication timeout
         * (1 s), so a hanging Redis is answered as an unavailable one before the authenticator's budget runs out; far
         * above a Redis read's tail, a few milliseconds.
         */
        val DEFAULT_READ_TIMEOUT: Duration = Duration.ofMillis(300)
    }
}
