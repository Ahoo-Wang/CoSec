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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.cosec.spi.social.SocialStatesFullException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * [RedisSocialStateStore] against real Redis: a state CoSec issued is taken once, by `GETDEL`, whichever instance
 * receives its callback (`IDN-20`), and a Redis failure throws. That it never blocks its caller is in
 * `SnapshotStoreBlockHoundTest`.
 */
class RedisSocialStateStoreTest {
    private val keyPrefix = "cosec:test:oauth:binding:${UUID.randomUUID()}:"
    private val store = RedisSocialStateStore(ReactiveStringRedisTemplate(factory), keyPrefix)

    @Test
    fun aStateIsTakenOnce(): Unit = runBlocking {
        val state = RandomIdGenerator.generate()
        store.put(state, "hash", Duration.ofMinutes(1))
        store.take(state).assert().isEqualTo("hash")
        store.take(state).assert().isNull()
        store.take(RandomIdGenerator.generate()).assert().isNull()
    }

    /** Of two callbacks holding one state, at most one finds it: the take is one atomic command. */
    @Test
    fun ofManyConcurrentTakesExactlyOneWins(): Unit = runBlocking {
        val state = RandomIdGenerator.generate()
        store.put(state, "hash", Duration.ofMinutes(1))
        val taken = List(32) { async(Dispatchers.Default) { store.take(state) } }.awaitAll()
        taken.filterNotNull().assert().containsExactly("hash")
    }

    /** The state lives under the deployment's prefix, for the lifetime it was issued for, and no longer. */
    @Test
    fun aStateLivesUnderThePrefixForItsLifetime(): Unit = runBlocking {
        val state = RandomIdGenerator.generate()
        store.put(state, "hash", Duration.ofSeconds(60))
        val redis = StringRedisTemplate(factory)
        redis.opsForValue().get(keyPrefix + state).assert().isEqualTo("hash")
        redis.getExpire(keyPrefix + state, TimeUnit.MILLISECONDS).assert().isBetween(1L, 60_000L)

        store.put(state, "short", Duration.ofMillis(100))
        Thread.sleep(200)
        store.take(state).assert().isNull()
    }

    /** Fail closed: an unreachable Redis is an error, never "no such state" and never a recorded state. */
    @Test
    fun anUnreachableRedisThrows() {
        val unreachable = LettuceConnectionFactory(
            RedisStandaloneConfiguration("localhost", 1),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build(),
        )
        unreachable.afterPropertiesSet()
        try {
            val failing = RedisSocialStateStore(ReactiveStringRedisTemplate(unreachable), keyPrefix)
            assertThrows<Exception> { runBlocking { failing.take("any") } }
            assertThrows<Exception> { runBlocking { failing.put("any", "hash", Duration.ofMinutes(1)) } }
        } finally {
            unreachable.destroy()
        }
    }

    /**
     * DR1-D-9: the deployment holds at most its bound of sign-ins in progress. Past it a start is refused and writes
     * nothing; the starts are counted on one key, so the script runs on a Redis Cluster as on one node, and that key
     * expires within two of the states' lifetimes, leaving nothing behind.
     */
    @Test
    fun startsPastTheBoundAreRefusedAndWriteNothing(): Unit = runBlocking {
        val bounded = RedisSocialStateStore(ReactiveStringRedisTemplate(factory), keyPrefix, maxStates = 3)
        val issued = List(3) { RandomIdGenerator.generate() }
        issued.forEach { bounded.put(it, "hash", Duration.ofSeconds(60)) }
        val refused = RandomIdGenerator.generate()
        assertThrows<SocialStatesFullException> { runBlocking { bounded.put(refused, "hash", Duration.ofSeconds(60)) } }
        val redis = StringRedisTemplate(factory)
        redis.hasKey(keyPrefix + refused).assert().isFalse()
        // Taking a state does not free its count: the bound is on starts within a lifetime, which hold every live state.
        bounded.take(issued.first()).assert().isEqualTo("hash")
        assertThrows<SocialStatesFullException> { runBlocking { bounded.put(refused, "hash", Duration.ofSeconds(60)) } }
        // One key, under the store's prefix: one slot on a Redis Cluster.
        redis.keys("$keyPrefix*").assert().containsExactlyInAnyOrder(
            keyPrefix + RedisSocialStateStore.COUNT_SUFFIX,
            *issued.drop(1).map { keyPrefix + it }.toTypedArray(),
        )
        val ttl = redis.getExpire(keyPrefix + RedisSocialStateStore.COUNT_SUFFIX, java.util.concurrent.TimeUnit.SECONDS)
        ttl.assert().isGreaterThan(0).isLessThanOrEqualTo(120)
    }

    companion object {
        private lateinit var factory: LettuceConnectionFactory

        @BeforeAll
        @JvmStatic
        fun setup() {
            factory = LettuceConnectionFactory(RedisStandaloneConfiguration())
            factory.afterPropertiesSet()
        }

        @AfterAll
        @JvmStatic
        fun destroy() {
            factory.destroy()
        }
    }
}
