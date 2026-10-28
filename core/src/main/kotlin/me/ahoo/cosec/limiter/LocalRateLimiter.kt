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

package me.ahoo.cosec.limiter

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * In-memory [RateLimiter], counting per JVM with the same sliding-window algorithm as the Redis one.
 *
 * Buckets idle for [expireAfterAccess] are dropped, and at most [maximumBuckets] are kept, so per-IP or
 * per-user rules cannot exhaust the heap. An evicted bucket starts again from zero.
 */
class LocalRateLimiter(
    maximumBuckets: Long = DEFAULT_MAXIMUM_BUCKETS,
    expireAfterAccess: Duration = DEFAULT_EXPIRE_AFTER_ACCESS,
    private val clock: () -> Long = System::currentTimeMillis
) : RateLimiter {
    companion object {
        const val DEFAULT_MAXIMUM_BUCKETS = 100_000L
        val DEFAULT_EXPIRE_AFTER_ACCESS: Duration = Duration.ofHours(1)
    }

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

    override fun tryAcquire(ruleId: String, bucket: String, rateLimit: RateLimit): Mono<RateLimitResult> =
        Mono.fromSupplier {
            acquire(windows.get(BucketKey(ruleId, bucket)) { Window() }, rateLimit, clock())
        }

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
}
