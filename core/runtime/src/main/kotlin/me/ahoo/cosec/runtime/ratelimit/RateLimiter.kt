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

import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.spi.ratelimit.RateLimit
import me.ahoo.cosec.spi.ratelimit.RateLimitResult
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
 * Finding a bucket takes no lock; admitting one reserves a slot of each bound first; only a sweep removes one, and only
 * the instance it found idle. A sweep cycle starts once per sweep interval (half the expiry, at most a minute), also
 * when the limiter is full, never earlier, and walks the buckets in chunks of [SWEEP_CHUNK]: each request advances a
 * running cycle by at most one chunk, so no request pays for the whole map. The cycle's iterator passes from one
 * request's thread to another's: only the holder of [sweeping] touches it, and releasing [sweeping] publishes its state
 * to the next holder; a `ConcurrentHashMap` iterator tolerates the map changing under it. A bucket that becomes
 * removable behind the iterator waits for the next cycle: nothing depends on a bucket being removed promptly, only on a
 * live one never being removed. [clock] is injectable, for tests and replays.
 */
class LocalRateLimiter(
    private val maximumBuckets: Long = DEFAULT_MAXIMUM_BUCKETS,
    private val maximumBucketsPerRule: Long = (maximumBuckets / 2).coerceAtLeast(1),
    expireAfterAccess: Duration = DEFAULT_EXPIRE_AFTER_ACCESS,
    private val clock: () -> Long = System::currentTimeMillis,
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

    /**
     * Thrown when a new bucket cannot be admitted: the rule cannot be counted, so its `strictFailure` decides. An answer,
     * not a bug: a flood on a full rule throws it on every request, so it has no stack trace to fill.
     */
    internal class LimiterFullException(message: String) : RuntimeException(message, null, false, false)

    private val windows = ConcurrentHashMap<BucketKey, Window>()
    private val admitted = AtomicLong()

    /**
     * Each rule's admitted buckets. An entry is never removed, even at zero: another thread may be about to increment
     * it, and the sweep reads it with `getValue`. It stays small because rule ids come from compiled documents, never
     * from requests.
     */
    private val admittedByRule = ConcurrentHashMap<String, AtomicLong>()
    private val expireMillis = expireAfterAccess.toMillis()
    private val sweepIntervalMillis = (expireMillis / 2).coerceIn(1, MAX_SWEEP_INTERVAL_MILLIS)
    private val nextSweep = AtomicLong(Long.MIN_VALUE)

    /** Held by the one request advancing the sweep cycle; guards [cycle]. */
    private val sweeping = AtomicBoolean()

    /** The running sweep cycle's position; null between cycles. Read and written only while holding [sweeping]. */
    private var cycle: Iterator<Map.Entry<BucketKey, Window>>? = null

    /** Whether a cycle is running: lets a request skip [sweeping] between cycles with one volatile read. */
    @Volatile
    private var cycleRunning = false

    /** How many buckets one step of a cycle visits at most; smaller in tests, to make cycles span requests. */
    internal var sweepChunk: Int = SWEEP_CHUNK

    /** The buckets the sweep has visited and the cycles it has finished, for tests. */
    internal val sweepVisits = AtomicLong()
    internal val sweepCycles = AtomicLong()

    override suspend fun tryAcquire(ruleId: String, bucket: String, limit: RateLimitSpec): RateLimitResult {
        val rateLimit = RateLimiter.window(limit)
        val now = clock()
        val swept = sweepStep(now)
        val key = BucketKey(ruleId, bucket)
        while (true) {
            val window = windows[key] ?: admit(key, rateLimit.windowMillis, now, swept)
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

    /**
     * Admits [key]'s bucket. Admission of one key is atomic (`computeIfAbsent` holds the key's bin), so a request that
     * races another for the same new key gets that one's bucket and counts into it, never "full" for a key that has a
     * bucket. A slot is reserved only by the admission that creates the bucket. Without a free slot, a request that has
     * not advanced the sweep yet ([swept]) advances it by one step first, and only a cycle that is running or due.
     */
    private fun admit(key: BucketKey, windowMillis: Long, now: Long, swept: Boolean): Window {
        val rule = admittedByRule.computeIfAbsent(key.ruleId) { AtomicLong() }
        tryAdmit(key, rule, windowMillis, now)?.let { return it }
        if (!swept) {
            sweepStep(now)
        }
        return tryAdmit(key, rule, windowMillis, now) ?: throw LimiterFullException(
            "The local rate limiter holds its maximum of buckets (rule [${key.ruleId}]: " +
                "${rule.get()} of $maximumBucketsPerRule, all: ${admitted.get()} of $maximumBuckets)."
        )
    }

    /** [key]'s bucket, created with a reserved slot if it has none; null when no slot is free. */
    private fun tryAdmit(key: BucketKey, rule: AtomicLong, windowMillis: Long, now: Long): Window? = try {
        // A mapping function cannot answer "none" in Kotlin: it throws, which leaves no mapping.
        windows.computeIfAbsent(key) { if (reserve(rule)) Window(windowMillis, now) else throw NoSlot }
    } catch (_: NoSlot) {
        null
    }

    /** No slot is free: a control-flow signal inside [tryAdmit], shared and without a stack trace. */
    private object NoSlot : RuntimeException(null, null, false, false) {
        private fun readResolve(): Any = NoSlot
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

    /**
     * Advances the sweep by one step: the running cycle by at most [sweepChunk] buckets, or the cycle that is due, from
     * its first bucket. Nothing between cycles, before the interval is up, or while another request holds [sweeping].
     * Whether this request took the step.
     */
    private fun sweepStep(now: Long): Boolean {
        if (!cycleRunning && now < nextSweep.get()) {
            return false
        }
        if (!sweeping.compareAndSet(false, true)) {
            return false
        }
        try {
            val running = cycle ?: startCycle(now) ?: return false
            var visited = 0
            while (visited < sweepChunk && running.hasNext()) {
                val (key, window) = running.next()
                visited++
                if (window.removable(now, expireMillis)) {
                    remove(key, window, now)
                }
            }
            sweepVisits.addAndGet(visited.toLong())
            if (!running.hasNext()) {
                cycle = null
                cycleRunning = false
                sweepCycles.incrementAndGet()
            }
            return true
        } finally {
            sweeping.set(false)
        }
    }

    /** The cycle that is due, from its first bucket; null when none is due. Called holding [sweeping]. */
    private fun startCycle(now: Long): Iterator<Map.Entry<BucketKey, Window>>? {
        if (now < nextSweep.get()) {
            return null
        }
        nextSweep.set(now + sweepIntervalMillis)
        return windows.entries.iterator().also {
            cycle = it
            cycleRunning = true
        }
    }

    /** Removes [window] if it is still [key]'s bucket and still removable, under its monitor. */
    private fun remove(key: BucketKey, window: Window, now: Long) {
        synchronized(window) {
            if (!window.retired && window.removable(now, expireMillis) && windows.remove(key, window)) {
                window.retired = true
                release(admittedByRule.getValue(key.ruleId))
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

        /** Buckets one request visits at most when it advances a sweep: measured at 20 to 75 microseconds. */
        internal const val SWEEP_CHUNK = 1_024
    }
}
