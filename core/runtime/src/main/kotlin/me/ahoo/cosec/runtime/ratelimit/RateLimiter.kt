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

package me.ahoo.cosec.runtime.ratelimit

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.model.decision.Obligation
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.spi.ratelimit.RateLimit
import me.ahoo.cosec.spi.ratelimit.RateLimitResult
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * An in-memory [RateLimiter], counting per JVM with the sliding window of the Redis one, without suspending.
 *
 * Buckets idle for [expireAfterAccess] are dropped and at most [maximumBuckets] are kept, so per-address or
 * per-principal rules cannot exhaust the heap; an evicted bucket starts from zero. [clock] is injectable, for tests and
 * replays.
 */
class LocalRateLimiter(
    maximumBuckets: Long = DEFAULT_MAXIMUM_BUCKETS,
    expireAfterAccess: Duration = DEFAULT_EXPIRE_AFTER_ACCESS,
    private val clock: () -> Long = System::currentTimeMillis,
) : RateLimiter {
    private data class BucketKey(val ruleId: String, val bucket: String)

    private class Window {
        var index = Long.MIN_VALUE
        var current = 0L
        var previous = 0L
    }

    private val windows: Cache<BucketKey, Window> = CacheBuilder.newBuilder()
        .maximumSize(maximumBuckets)
        .expireAfterAccess(expireAfterAccess)
        .build()

    override suspend fun tryAcquire(ruleId: String, bucket: String, limit: RateLimitSpec): RateLimitResult =
        acquire(windows.get(BucketKey(ruleId, bucket)) { Window() }, RateLimiter.window(limit), clock())

    private fun acquire(window: Window, rateLimit: RateLimit, nowMillis: Long): RateLimitResult {
        val windowMillis = rateLimit.windowMillis
        val index = nowMillis / windowMillis
        synchronized(window) {
            when (index) {
                window.index -> Unit
                window.index + 1 -> {
                    window.previous = window.current
                    window.current = 0
                }

                else -> {
                    window.previous = 0
                    window.current = 0
                }
            }
            window.index = index
            val previousWeight = (windowMillis - nowMillis % windowMillis).toDouble() / windowMillis
            val estimate = window.previous * previousWeight + window.current
            val allowed = estimate < rateLimit.limitPerWindow
            if (allowed) {
                window.current++
            }
            return rateLimit.resultOf(allowed, estimate, nowMillis)
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_BUCKETS = 100_000L
        val DEFAULT_EXPIRE_AFTER_ACCESS: Duration = Duration.ofHours(1)
    }
}

/** Logs rate limiter failures at most once per [interval]: under an outage every request would log one. */
internal class RateLimitFailures(private val interval: Duration = Duration.ofSeconds(10)) {
    private val last = AtomicLong(Long.MIN_VALUE)

    fun report(obligation: Obligation.RateLimit, failure: Throwable) {
        val now = System.nanoTime()
        val previous = last.get()
        if (previous != Long.MIN_VALUE && now - previous < interval.toNanos()) {
            return
        }
        if (!last.compareAndSet(previous, now)) {
            return
        }
        if (obligation.strictFailure) {
            log.error(failure) { "Rate limiter unavailable: rule [${obligation.ruleId}] refuses the request." }
        } else {
            log.warn(failure) { "Rate limiter unavailable: rule [${obligation.ruleId}] is skipped." }
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
