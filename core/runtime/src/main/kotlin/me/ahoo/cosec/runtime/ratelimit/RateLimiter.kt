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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.model.decision.Obligation
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.spi.ratelimit.RateLimit
import me.ahoo.cosec.spi.ratelimit.RateLimitResult
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * An in-memory [RateLimiter], counting per JVM with the sliding window of the Redis one, without suspending.
 *
 * A bucket (a rule's count for one key) lives until nothing it holds can change an answer any more: idle for
 * [expireAfterAccess], and for two of its rule's windows, when both of its counters are stale and the estimate would read
 * zero anyway. So no pause of a client, however long within its window, resets what it has used (`OBL-5`).
 *
 * At most [maximumBuckets] buckets exist, and at most [maximumBucketsPerRule] of one rule, so a rule whose key a client
 * can vary fills only its own share. An existing bucket is never evicted to make room: its count is the state the rule
 * exists to keep, and dropping it would hand its key fresh permits. A bucket that cannot be admitted cannot be counted,
 * so [tryAcquire] throws and the rule's `strictFailure` decides, as for any limiter that cannot answer (`OBL-6`).
 *
 * Finding a bucket takes no lock; admitting one reserves a slot of each bound first; only a sweep removes one, at most
 * once per sweep interval, and only the instance it found idle. [clock] is injectable, for tests and replays.
 */
class LocalRateLimiter(
    private val maximumBuckets: Long = DEFAULT_MAXIMUM_BUCKETS,
    expireAfterAccess: Duration = DEFAULT_EXPIRE_AFTER_ACCESS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maximumBucketsPerRule: Long = (maximumBuckets / 2).coerceAtLeast(1),
) : RateLimiter {
    init {
        require(maximumBuckets > 0) { "maximumBuckets must be positive, not $maximumBuckets." }
        require(maximumBucketsPerRule in 1..maximumBuckets) {
            "maximumBucketsPerRule must be between 1 and maximumBuckets ($maximumBuckets), not $maximumBucketsPerRule."
        }
        require(!expireAfterAccess.isNegative && !expireAfterAccess.isZero) {
            "expireAfterAccess must be positive, not $expireAfterAccess."
        }
    }

    private data class BucketKey(val ruleId: String, val bucket: String)

    /** One bucket: its counters, guarded by its own monitor, which [retired] is read and written under too. */
    private class Window(var windowMillis: Long, @Volatile var lastAccess: Long) {
        var index = Long.MIN_VALUE
        var current = 0L
        var previous = 0L
        var retired = false

        fun removable(now: Long, expireMillis: Long): Boolean = now - lastAccess > maxOf(expireMillis, 2 * windowMillis)
    }

    /** Thrown when a new bucket cannot be admitted: the rule cannot be counted, so its `strictFailure` decides. */
    internal class LimiterFullException(message: String) : IllegalStateException(message)

    private val windows = ConcurrentHashMap<BucketKey, Window>()
    private val admitted = AtomicLong()
    private val admittedByRule = ConcurrentHashMap<String, AtomicLong>()
    private val expireMillis = expireAfterAccess.toMillis()
    private val sweepIntervalMillis = (expireMillis / 2).coerceIn(1, MAX_SWEEP_INTERVAL_MILLIS)
    private val nextSweep = AtomicLong(Long.MIN_VALUE)

    override suspend fun tryAcquire(ruleId: String, bucket: String, limit: RateLimitSpec): RateLimitResult {
        val rateLimit = RateLimiter.window(limit)
        val now = clock()
        sweepIfDue(now)
        val key = BucketKey(ruleId, bucket)
        while (true) {
            val window = windows[key] ?: admit(key, rateLimit.windowMillis, now)
            synchronized(window) {
                // Removed by a sweep between the lookup and the lock: count into the bucket that replaces it.
                if (!window.retired) {
                    window.lastAccess = now
                    window.windowMillis = rateLimit.windowMillis
                    return acquire(window, rateLimit, now)
                }
            }
        }
    }

    private fun admit(key: BucketKey, windowMillis: Long, now: Long): Window {
        val rule = admittedByRule.computeIfAbsent(key.ruleId) { AtomicLong() }
        if (!reserve(rule)) {
            sweepIfDue(now)
            if (!reserve(rule)) {
                throw LimiterFullException(
                    "The local rate limiter holds its maximum of buckets (rule [${key.ruleId}]: " +
                        "${rule.get()} of $maximumBucketsPerRule, all: ${admitted.get()} of $maximumBuckets)."
                )
            }
        }
        val created = Window(windowMillis, now)
        val existing = windows.putIfAbsent(key, created) ?: return created
        release(rule)
        return existing
    }

    /** A slot of the rule's bound and of the total, or neither. */
    private fun reserve(rule: AtomicLong): Boolean {
        if (rule.incrementAndGet() > maximumBucketsPerRule) {
            rule.decrementAndGet()
            return false
        }
        if (admitted.incrementAndGet() > maximumBuckets) {
            admitted.decrementAndGet()
            rule.decrementAndGet()
            return false
        }
        return true
    }

    private fun release(rule: AtomicLong) {
        rule.decrementAndGet()
        admitted.decrementAndGet()
    }

    private fun sweepIfDue(now: Long) {
        val due = nextSweep.get()
        if (now >= due && nextSweep.compareAndSet(due, now + sweepIntervalMillis)) {
            sweep(now)
        }
    }

    /** Removes the buckets that can no longer change an answer, each by its own instance. */
    private fun sweep(now: Long) {
        for ((key, window) in windows) {
            if (!window.removable(now, expireMillis)) continue
            synchronized(window) {
                if (!window.retired && window.removable(now, expireMillis) && windows.remove(key, window)) {
                    window.retired = true
                    release(admittedByRule.getValue(key.ruleId))
                }
            }
        }
    }

    /** What the buckets and their counters hold, for tests: the map's size, the total and each rule's count. */
    internal fun held(): Triple<Int, Long, Map<String, Long>> =
        Triple(windows.size, admitted.get(), admittedByRule.mapValues { it.value.get() })

    private fun acquire(window: Window, rateLimit: RateLimit, nowMillis: Long): RateLimitResult {
        val windowMillis = rateLimit.windowMillis
        val index = nowMillis / windowMillis
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

    companion object {
        const val DEFAULT_MAXIMUM_BUCKETS = 100_000L
        val DEFAULT_EXPIRE_AFTER_ACCESS: Duration = Duration.ofHours(1)
        private const val MAX_SWEEP_INTERVAL_MILLIS = 60_000L
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
