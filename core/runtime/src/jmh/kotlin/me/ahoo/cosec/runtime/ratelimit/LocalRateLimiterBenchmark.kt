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
import me.ahoo.cosec.spi.ratelimit.RateLimitResult
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Threads
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * `LocalRateLimiter.tryAcquire` on buckets it already holds: the cost a permitted request pays per rule when no Redis
 * is configured. `buckets` is how many keys the calls cycle over (1: one hot bucket; 10000: a map that does not fit the
 * caches). The limit is high enough that every call is allowed and counted. `contended` runs four threads on the same
 * keys, so they meet on one bucket's monitor.
 */
@State(Scope.Benchmark)
open class LocalRateLimiterBenchmark {
    @Param("1", "10000")
    var buckets: Int = 0

    private val limit = RateLimitSpec(1.0e9, 1)
    private lateinit var limiter: LocalRateLimiter
    private lateinit var keys: Array<String>
    private val next = AtomicInteger()

    @Setup
    fun setup() {
        limiter = LocalRateLimiter()
        keys = Array(buckets) { "client-$it" }
        keys.forEach { acquire(it) }
    }

    private fun acquire(key: String): RateLimitResult = completed { limiter.tryAcquire("platform/api", key, limit) }

    private fun nextKey(): String = keys[Math.floorMod(next.getAndIncrement(), buckets)]

    @Benchmark
    fun acquire(): RateLimitResult = acquire(nextKey())

    @Benchmark
    @Threads(4)
    fun contended(): RateLimitResult = acquire(nextKey())

    private companion object {
        /** `tryAcquire` of the local limiter never suspends: run it to completion on the calling thread. */
        fun <T> completed(block: suspend () -> T): T {
            var result: Result<T>? = null
            block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
            return checkNotNull(result) { "LocalRateLimiter.tryAcquire suspended" }.getOrThrow()
        }
    }
}
