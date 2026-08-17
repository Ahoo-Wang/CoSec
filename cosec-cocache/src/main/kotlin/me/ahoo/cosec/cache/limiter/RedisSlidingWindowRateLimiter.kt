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
package me.ahoo.cosec.cache.limiter

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.security.MessageDigest

/**
 * Sliding-window (interpolated counters) rate limiter backed by Redis.
 *
 * The whole check-and-count happens atomically in a single Lua script, so the
 * limit is enforced cluster-wide. State is two counters per window (current and
 * previous, weighted by elapsed time), which bounds memory to O(1) per group and
 * avoids the boundary burst hole of a plain fixed window.
 *
 * Redis keys expire naturally after two windows, so per-group keys never leak.
 */
class RedisSlidingWindowRateLimiter(
    private val stringRedisTemplate: StringRedisTemplate,
    keyPrefix: String,
    private val permitsPerSecond: Double,
    private val windowSeconds: Long,
    private val strictFailure: Boolean
) {
    companion object {
        private val log = KotlinLogging.logger {}

        /** Script resource next to this class, see `redis-rate-limiter.lua` in the module resources. */
        private const val SCRIPT_PATH = "redis-rate-limiter.lua"

        private const val GLOBAL_GROUP = "global"

        /** Distinguishes grouped keys from the global key, so a group literally named "global" cannot collide. */
        private const val GROUP_PREFIX = "g:"

        /** Emit at most one failure log per interval to avoid a log storm during an outage. */
        private const val FAILURE_LOG_INTERVAL_MS = 10_000L

        private val SCRIPT: DefaultRedisScript<Long> = DefaultRedisScript(
            loadScript(),
            Long::class.javaObjectType,
        )

        private fun loadScript(): String {
            val script = RedisSlidingWindowRateLimiter::class.java.getResourceAsStream(SCRIPT_PATH)
                ?: error("Redis rate limiter script [$SCRIPT_PATH] not found on the classpath.")
            return script.bufferedReader().use { it.readText() }
        }

        /**
         * Preloads the Lua script so the very first concurrent burst never races
         * script loading: on a fresh Redis, concurrent EVALSHA calls can hit
         * NOSCRIPT while the fallback EVAL is in flight, and a missed fallback
         * would surface as a failure (fail-open). Warm up at startup (or in
         * tests) to close that window. Uses a dedicated key, so no quota is touched.
         */
        fun warmUp(stringRedisTemplate: StringRedisTemplate) {
            stringRedisTemplate.execute(
                SCRIPT,
                listOf("warmup", "warmup-prev"),
                "1",
                "1000",
                "0",
            )
        }

        private fun sha1Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(12)
        }
    }

    init {
        require(permitsPerSecond > 0) { "permitsPerSecond must be positive, but was [$permitsPerSecond]." }
        require(windowSeconds >= 1) { "windowSeconds must be >= 1, but was [$windowSeconds]." }
    }

    /**
     * Config-derived key segment, so policies with different limits never share
     * counters while policies with identical limits share one cluster-wide quota.
     */
    private val configHash: String = sha1Hex("$permitsPerSecond|$windowSeconds|$strictFailure")

    private val keyPrefix: String = keyPrefix.trimEnd(':')

    private val limitPerWindow: Double = permitsPerSecond * windowSeconds

    @Volatile
    private var lastFailureLogAt = 0L

    fun tryAcquire(): Boolean = tryAcquireInternal(GLOBAL_GROUP, System.currentTimeMillis())

    fun tryAcquire(groupValue: String): Boolean =
        tryAcquireInternal(GROUP_PREFIX + groupValue, System.currentTimeMillis())

    internal fun tryAcquireInternal(groupKey: String, nowMs: Long): Boolean {
        val windowMs = windowSeconds * 1_000
        val windowIndex = nowMs / windowMs
        val baseKey = "$keyPrefix:$configHash:$groupKey"
        val result = try {
            stringRedisTemplate.execute(
                SCRIPT,
                listOf("$baseKey:$windowIndex", "$baseKey:${windowIndex - 1}"),
                limitPerWindow.toString(),
                windowMs.toString(),
                nowMs.toString(),
            )
        } catch (cause: DataAccessException) {
            // Any Redis failure (connectivity, script error, timeout) surfaces as a
            // DataAccessException and takes the configured failure mode.
            return handleFailure(cause)
        }
        if (result == 1L) {
            return true
        }
        throw TooManyRequestsException("Rate limit exceeded - Group[$groupKey]!")
    }

    private fun handleFailure(cause: DataAccessException): Boolean {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastFailureLogAt >= FAILURE_LOG_INTERVAL_MS) {
            lastFailureLogAt = nowMs
            if (strictFailure) {
                log.error(cause) {
                    "Redis rate limiter unavailable - failing closed."
                }
            } else {
                log.warn(cause) {
                    "Redis rate limiter unavailable - failing open."
                }
            }
        }
        if (strictFailure) {
            throw TooManyRequestsException("Rate limiter unavailable - denied by strictFailure.")
        }
        return true
    }
}
