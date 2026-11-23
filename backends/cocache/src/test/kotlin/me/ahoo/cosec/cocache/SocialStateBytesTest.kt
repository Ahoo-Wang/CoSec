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

import io.lettuce.core.RedisClient
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * DR1-D-9: what one sign-in in progress holds in Redis, by `MEMORY USAGE`: JustAuth's `oauth:state:<state>` (the state
 * as its value) and CoSec's `oauth:binding:<state>` (the binding's SHA-256), both with their lifetime, under a prefix as
 * long as a deployment's. Holds [RedisSocialStateStore.STATE_BYTES] to it: never below, at most twice.
 */
class SocialStateBytesTest {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    private fun fresh(bytes: Int): String = encoder.encodeToString(ByteArray(bytes).also(SecureRandom()::nextBytes))

    @Test
    fun `the bytes a sign-in is counted at are what its two keys hold`() {
        val prefix = "cosec-${UUID.randomUUID()}"
        val factory = LettuceConnectionFactory(RedisStandaloneConfiguration()).apply { afterPropertiesSet() }
        val client = RedisClient.create("redis://localhost:6379")
        try {
            val commands = client.connect().sync()
            val store = RedisSocialStateStore(ReactiveStringRedisTemplate(factory), "$prefix:oauth:binding:")
            val measured = List(SAMPLES) {
                val state = fresh(RANDOM_BYTES)
                val binding = encoder.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(fresh(RANDOM_BYTES).toByteArray())
                )
                runBlocking { store.put(state, binding, LIFETIME) }
                commands.psetex("$prefix:oauth:state:$state", LIFETIME.toMillis(), state)
                commands.memoryUsage("$prefix:oauth:state:$state") + commands.memoryUsage("$prefix:oauth:binding:$state")
            }.max()
            println(
                "A social sign-in in progress: $measured B in Redis; the constant is ${RedisSocialStateStore.STATE_BYTES} B"
            )
            RedisSocialStateStore.STATE_BYTES.assert().isGreaterThanOrEqualTo(
                measured
            ).isLessThanOrEqualTo(measured * 2)
            commands.keys("$prefix:*").forEach { commands.del(it) }
        } finally {
            client.shutdown()
            factory.destroy()
        }
    }

    private companion object {
        const val SAMPLES = 20
        const val RANDOM_BYTES = 16
        val LIFETIME: Duration = Duration.ofMinutes(3)
    }
}
