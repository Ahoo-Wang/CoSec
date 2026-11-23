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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The count a rule keeps is the state it exists for: neither an idle pause shorter than the rule's window (F1) nor the
 * arrival of other buckets, of this rule or another (F2), may reset it.
 */
class LocalRateLimiterCountsTest {
    private var now = 0L

    /** Two permits a day. */
    private val daily = RateLimitSpec(2.0 / SECONDS_PER_DAY, SECONDS_PER_DAY)

    /** F1: a client that pauses for the idle expiry, within a one-day window, is still at its limit. */
    @Test
    fun `an idle pause shorter than the window keeps the count`() = runBlocking<Unit> {
        val limiter = LocalRateLimiter(expireAfterAccess = Duration.ofMillis(EXPIRY_MILLIS), clock = { now })
        now = 0
        limiter.tryAcquire("daily", "alice", daily).allowed.assert().isTrue()
        limiter.tryAcquire("daily", "alice", daily).allowed.assert().isTrue()
        limiter.tryAcquire("daily", "alice", daily).allowed.assert().isFalse()
        // Idle for longer than the expiry, by the limiter's own clock and by the wall clock, and still within the day.
        Thread.sleep(EXPIRY_MILLIS * 3)
        now = EXPIRY_MILLIS * 3
        limiter.tryAcquire("daily", "alice", daily).allowed.assert().describedAs("still within the day").isFalse()
    }

    /** F2: other buckets arriving, beyond the bound, do not reset a live count. */
    @Test
    fun `a flood of other buckets keeps a live count`() = runBlocking<Unit> {
        val limiter = LocalRateLimiter(maximumBuckets = FLOOD_BOUND, clock = { now })
        val once = RateLimitSpec(1.0, 60)
        limiter.tryAcquire("a", "alice", once.copy(permitsPerSecond = 1.0 / 60)).allowed.assert().isTrue()
        repeat(FLOOD) { limiter.runCatching { tryAcquire("b", "key-$it", once) } }
        limiter.tryAcquire("a", "alice", once.copy(permitsPerSecond = 1.0 / 60)).allowed
            .assert().describedAs("alice's bucket of rule a after a flood on rule b").isFalse()
    }

    /** Ten permits a second. */
    private val tenPerSecond = RateLimitSpec(10.0, 1)

    /** How many of [calls] requests at each time of [times] are allowed, by a fresh limiter on that clock. */
    private fun allowedAt(times: List<Long>, calls: Int = 1): List<Int> = runBlocking {
        val limiter = LocalRateLimiter(clock = { now })
        times.map { at ->
            now = at
            (1..calls).count { limiter.tryAcquire("api", "alice", tenPerSecond).allowed }
        }
    }

    /**
     * OBL-5, DR1-C-10: a time behind the bucket's window (read before another request moved the window on, or a clock
     * that went back once) counts in the window the bucket is in, and changes no later answer: the next window is
     * decided as if that request had come at its start.
     */
    @Test
    fun `a late time changes no answer of the window after it`() {
        val reference = allowedAt(listOf(10_000, 11_000, 11_000, 11_002), calls = 10)
        val late = allowedAt(listOf(10_000, 11_000, 10_999, 11_002), calls = 10)
        late.assert().isEqualTo(reference)
        // At 11,002 the previous window's ten still weigh 9.98: one permit, never the ten a cleared count gave.
        late.last().assert().isLessThanOrEqualTo(1)
    }

    /** OBL-5, DR1-C-10: a clock set back keeps counting in the window the bucket reached; it never clears it. */
    @Test
    fun `a clock set back keeps the window it reached`() {
        val reference = allowedAt(listOf(20_000, 20_500, 20_500, 20_500), calls = 5)
        val setBack = allowedAt(listOf(20_000, 20_500, 15_000, 9_000), calls = 5)
        setBack.assert().isEqualTo(reference)
        setBack.drop(2).sum().assert().isZero()
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
        const val EXPIRY_MILLIS = 200L
        const val FLOOD_BOUND = 8L
        const val FLOOD = 500
    }
}
