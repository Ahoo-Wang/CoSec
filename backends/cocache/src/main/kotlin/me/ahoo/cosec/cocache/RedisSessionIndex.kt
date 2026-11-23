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

import kotlinx.coroutines.future.await
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.token.SessionIndex
import me.ahoo.cosec.spi.token.TokenFamily
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration
import java.time.Instant

/**
 * The [SessionIndex] on Redis: one sorted set per principal and tenant, `<keyPrefix><n>:<tenant>:<principal>`, where
 * `<n>` is the tenant id's length, so that ids holding `:` never share a key; of session ids scored by when each began, in Redis's own time. One script admits a session, pushes out the earliest past the
 * limit and keeps the key at least as long as the session can live (`session-index.lua`); it touches that one key only,
 * so it runs on a Redis Cluster. It runs through the reactive client and awaits it: no thread waits for Redis. A
 * failure throws, so the login fails rather than create a session the index does not count.
 */
@InternalCoSecApi
class RedisSessionIndex(
    private val redisTemplate: ReactiveStringRedisTemplate,
    /** `<keyPrefix>:token:sessions:`, under the deployment's prefix (`SNP-9`). */
    private val keyPrefix: String,
) : SessionIndex {
    override suspend fun admit(family: TokenFamily, now: Instant, keepFor: Duration, limit: Int): List<String> {
        val tenant = family.tenant.value
        val key = "$keyPrefix${tenant.length}:$tenant:${family.principal.value}"
        val life = keepFor.toMillis().coerceAtLeast(1)
        return redisTemplate.execute(SCRIPT, listOf(key), listOf(family.id, limit.toString(), life.toString()))
            .next().toFuture().await()
            ?.map { it.toString() }
            ?: error("The session index script returned no reply.")
    }

    private companion object {
        const val SCRIPT_PATH = "session-index.lua"

        @Suppress("UNCHECKED_CAST")
        val SCRIPT: DefaultRedisScript<List<Any>> = DefaultRedisScript(
            checkNotNull(RedisSessionIndex::class.java.getResourceAsStream(SCRIPT_PATH)) {
                "The session index script [$SCRIPT_PATH] is not on the classpath."
            }.bufferedReader().use { it.readText() },
            List::class.java as Class<List<Any>>,
        )
    }
}
