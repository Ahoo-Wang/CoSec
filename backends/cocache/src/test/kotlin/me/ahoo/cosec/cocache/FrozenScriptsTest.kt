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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.limiter.ReactiveRedisRateLimiter
import me.ahoo.cosec.cocache.limiter.ReactiveRedisRateLimiterTest
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.social.SocialStatesFullException
import me.ahoo.cosec.spi.token.TokenFamily
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * What CoSec's scripts store, frozen within 8.x (RFC 0014 R6, `SNP-26`): the fields, their meaning and how a window is
 * cut, against real Redis. Each script is also run beside its frozen copy (`frozen/<script>.lua`, the text of the release that
 * froze it) on one key, as instances of two releases would, and the two together never count past what either allows.
 * The script's text may change, a fix included, so long as these hold.
 */
class FrozenScriptsTest {
    private val factory = ReactiveRedisRateLimiterTest.connectionFactory("localhost", 6379)
    private val redis = StringRedisTemplate(factory)
    private val reactive = ReactiveStringRedisTemplate(factory)
    private val prefix = "cosec-frozen-${UUID.randomUUID()}"

    @AfterEach
    fun close() {
        redis.keys("$prefix*").takeIf { it.isNotEmpty() }?.let(redis::delete)
        factory.destroy()
    }

    private fun redisNowMillis(): Long {
        val time = redis.execute(RedisCallback { it.serverCommands().time(TimeUnit.MILLISECONDS) })
        return checkNotNull(time)
    }

    private inline fun <reified T : Any> frozenScript(name: String): DefaultRedisScript<T> = DefaultRedisScript(
        checkNotNull(FrozenScriptsTest::class.java.getResourceAsStream("frozen/$name")) { "frozen/$name" }
            .bufferedReader().use { it.readText() },
        T::class.java,
    )

    private fun hold(entry: FrozenLayout, sample: String, expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError(frozen(entry, sample, expected, actual))
    }

    @Test
    fun aRateLimitCounterStoresItsWindowAndTwoCounts() = runBlocking {
        val limiter = ReactiveRedisRateLimiter(reactive, CoSecCacheSpecs.rateLimiterPrefix(prefix))
        val quota = ReactiveRedisRateLimiterTest.quotaOf(10)
        limiter.tryAcquire("platform/api", "k:alice", quota)
        val key = limiter.keyOf("platform/api", "k:alice")
        val stored = redis.opsForHash<String, String>().entries(key)
        val window = redisNowMillis() / quota.windowMillis
        val entry = FrozenLayout.RATE_LIMIT_COUNTERS
        hold(entry, "the fields of a counter", setOf("w", "c", "p"), stored.keys)
        hold(entry, "w is floor(TIME / window)", window.toString(), stored["w"])
        hold(entry, "c counts this window", "1", stored["c"])
        hold(entry, "p counts the last window", "0", stored["p"])
        val expiry = checkNotNull(redis.getExpire(key, TimeUnit.MILLISECONDS))
        hold(
            entry,
            "a counter expires after two windows",
            true,
            expiry in (quota.windowMillis * 2 - 60_000)..quota.windowMillis * 2
        )
    }

    @Test
    fun aRateLimitCounterAndItsFrozenScriptCountOneQuota() = runBlocking {
        val limiter = ReactiveRedisRateLimiter(reactive, CoSecCacheSpecs.rateLimiterPrefix(prefix))
        val quota = ReactiveRedisRateLimiterTest.quotaOf(10)
        val key = limiter.keyOf("platform/api", "k:alice")
        val frozen = frozenScript<List<*>>("redis-rate-limiter.lua")
        var allowed = 0
        repeat(30) { attempt ->
            val permitted = if (attempt % 2 == 0) {
                limiter.tryAcquire("platform/api", "k:alice", quota).allowed
            } else {
                redis.execute(frozen, listOf(key), quota.limitPerWindow.toString(), quota.windowMillis.toString())[0] == 1L
            }
            if (permitted) allowed++
        }
        hold(FrozenLayout.RATE_LIMIT_COUNTERS, "two releases on one counter", 10, allowed)
    }

    private fun family(index: Int): TokenFamily {
        val now = Instant.now()
        val tenant = TenantId("acme")
        val alice = PrincipalId("alice")
        return TokenFamily("sid-$index", tenant, alice, now, now.plus(Duration.ofDays(1)), 1, null, false)
    }

    @Test
    fun aSessionIndexAndItsFrozenScriptKeepTheNewestSessions() = runBlocking {
        val prefixed = CoSecCacheSpecs.sessionIndexPrefix(prefix)
        val index = RedisSessionIndex(reactive, prefixed)
        val key = "${prefixed}4:acme:alice"
        val frozen = frozenScript<List<*>>("session-index.lua")
        val limit = 64
        val life = Duration.ofDays(1)
        // Odd in number, so that the current script has the last word.
        repeat(71) { n ->
            if (n % 2 == 0) {
                index.admit(family(n), Instant.now(), life, limit)
            } else {
                redis.execute(frozen, listOf(key), "sid-$n", limit.toString(), life.toMillis().toString(), "")
            }
        }
        val entry = FrozenLayout.SESSION_INDEX
        val kept = checkNotNull(redis.opsForZSet().rangeWithScores(key, 0, -1)).toList()
        hold(entry, "two releases on one index keep the limit", limit, kept.size)
        hold(entry, "the members are session ids, oldest first", (7 until 71).map { "sid-$it" }, kept.map { it.value })
        hold(
            entry,
            "the scores increase",
            true,
            kept.zipWithNext().all { (a, b) -> checkNotNull(a.score) < checkNotNull(b.score) }
        )
        hold(
            entry,
            "a score is Redis's TIME in milliseconds",
            true,
            checkNotNull(kept.last().score).toLong() in (redisNowMillis() - 60_000)..(redisNowMillis() + 1_000)
        )
    }

    @Test
    fun aSocialCountAndItsFrozenScriptHoldOneBound() = runBlocking {
        val prefixed = CoSecCacheSpecs.socialBindingPrefix(prefix)
        val store = RedisSocialStateStore(reactive, prefixed, maxStates = 5)
        val countKey = prefixed + RedisSocialStateStore.COUNT_SUFFIX
        val frozen = frozenScript<Long>("social-states.lua")
        val lifetime = Duration.ofMinutes(10)
        var started = 0
        repeat(10) { n ->
            val counted = if (n % 2 == 0) {
                runCatchingFull { store.put("state-$n", "binding", lifetime) }
            } else {
                redis.execute(frozen, listOf(countKey), lifetime.toSeconds().toString(), "5") == 1L
            }
            if (counted) started++
        }
        val entry = FrozenLayout.SOCIAL_COUNT
        hold(entry, "two releases on one count hold the bound", 5, started)
        val window = redisNowMillis() / 1000 / lifetime.toSeconds()
        hold(
            entry,
            "a field is the window's number in decimal",
            setOf(window.toString()),
            redis.opsForHash<String, String>().keys(countKey)
        )
    }

    /** True when [put] started a sign-in, false when the bound refused it. */
    private suspend fun runCatchingFull(put: suspend () -> Unit): Boolean = try {
        put()
        true
    } catch (_: SocialStatesFullException) {
        false
    }

    companion object {
        /** The entries whose samples are here, beside `FrozenLayoutTest`'s. */
        internal val ENTRIES: Set<FrozenLayout> = setOf(
            FrozenLayout.RATE_LIMIT_COUNTERS,
            FrozenLayout.SESSION_INDEX,
            FrozenLayout.SOCIAL_COUNT,
        )
    }
}
