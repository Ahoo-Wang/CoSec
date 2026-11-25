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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.compat.side

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.RedisSessionIndex
import me.ahoo.cosec.cocache.RedisSocialStateStore
import me.ahoo.cosec.cocache.limiter.ReactiveRedisRateLimiter
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.spi.token.TokenFamily
import java.time.Duration
import java.time.Instant

/**
 * The counts two versions keep on the same keys (MIX-5, MIX-8, MIX-9), through this version's scripts: `limit.acquire`
 * takes a permit of a rule, `session.admit` counts a principal's session and answers the ones it pushes out,
 * `social.put` and `social.take` record and consume a login state.
 */
internal object CounterOperations {
    private const val MILLIS = 1_000L

    val ALL: Map<String, (Map<String, String>) -> String> = mapOf(
        "limit.acquire" to { a ->
            val spec = RateLimitSpec(a.getValue("permits").toDouble(), a.getValue("window").toLong())
            val result = runBlocking {
                ReactiveRedisRateLimiter(Redis.reactive, a.getValue("prefix")).tryAcquire(a.getValue("rule"), a.getValue("bucket"), spec)
            }
            "allowed=${result.allowed}"
        },
        // A counter as if the window before this one had counted `previous`: the next take rolls it into `p` (MIX-8).
        "limit.seed" to { a ->
            val windowMs = a.getValue("window").toLong() * MILLIS
            val nowMs = checkNotNull(Redis.strings.execute { connection -> connection.serverCommands().time() })
            val key = "${a.getValue("prefix")}:${a.getValue("rule")}:${a.getValue("bucket")}"
            Redis.strings.opsForHash<String, String>()
                .putAll(key, mapOf("w" to (nowMs / windowMs - 1).toString(), "c" to a.getValue("previous"), "p" to "0"))
            Redis.strings.expire(key, Duration.ofMillis(windowMs * 2))
            "elapsed=${nowMs % windowMs}"
        },
        // How far into its window Redis's clock is, for a window of `window` seconds: what the script weighs `p` by.
        "limit.elapsed" to { a ->
            val windowMs = a.getValue("window").toLong() * MILLIS
            val nowMs = checkNotNull(Redis.strings.execute { connection -> connection.serverCommands().time() })
            "elapsed=${nowMs % windowMs}"
        },
        "session.admit" to { a ->
            val now = Instant.ofEpochMilli(a.getValue("now").toLong())
            val family = TokenFamily(
                a.getValue("id"), TenantId("acme"), PrincipalId(a.getValue("principal")), now,
                now.plus(Duration.ofDays(1)), 1, null, false, null,
            )
            runBlocking {
                RedisSessionIndex(Redis.reactive, a.getValue("prefix"))
                    .admit(family, now, Duration.ofDays(1), a.getValue("limit").toInt())
            }.joinToString(",")
        },
        "social.put" to { a ->
            runBlocking {
                RedisSocialStateStore(Redis.reactive, a.getValue("prefix"), a.getValue("max").toLong())
                    .put(a.getValue("state"), a.getValue("binding"), Duration.ofMinutes(5))
            }
            "ok"
        },
        "social.take" to { a ->
            runBlocking { RedisSocialStateStore(Redis.reactive, a.getValue("prefix")).take(a.getValue("state")) } ?: "none"
        },
    )
}
