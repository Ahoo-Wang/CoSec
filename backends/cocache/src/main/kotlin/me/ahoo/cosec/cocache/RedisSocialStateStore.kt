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
import me.ahoo.cosec.spi.social.SocialStateStore
import me.ahoo.cosec.spi.social.SocialStatesFullException
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration

/**
 * The [SocialStateStore] on Redis: `<keyPrefix><state>` holds the binding hash for the state's lifetime, and [take] is
 * `GETDEL` (Redis 6.2), so a state is taken once whichever instance receives its callback. It runs through the reactive
 * client and awaits it, as [RedisApiKeyReader] does: the caller's thread, which may be an event loop, never waits for
 * Redis, and an anonymous flood of sign-in starts holds no thread. A Redis failure throws, so the sign-in fails rather
 * than proceed unchecked (`IDN-20`); a state a cancelled [take] removed is spent, which fails that sign-in.
 *
 * A state is written with the lifetime it is issued for, so none outlives its sign-in. Anyone can start a sign-in, and
 * Redis must not evict (`SNP-3`), so the deployment holds at most [maxStates] (DR1-D-9): before a state is written, a
 * script counts the starts of the deployment in windows of the state's lifetime on one key, [countKey], and past the
 * bound refuses with [SocialStatesFullException], writing nothing (`social-states.lua`).
 */
class RedisSocialStateStore @InternalCoSecApi constructor(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val keyPrefix: String,
    /** The most states the deployment holds, counted with [countKey]. */
    private val maxStates: Long = DEFAULT_MAX_STATES,
    /** The one key the starts are counted on. */
    private val countKey: String = keyPrefix + COUNT_SUFFIX,
) : SocialStateStore {
    init {
        require(maxStates >= 1) { "The deployment holds at least one social sign-in state, not $maxStates." }
    }

    override suspend fun put(state: String, bindingHash: String, lifetime: Duration) {
        val counted = redisTemplate.execute(
            SCRIPT,
            listOf(countKey),
            listOf(lifetime.toSeconds().coerceAtLeast(1).toString(), maxStates.toString()),
        ).next().toFuture().await() ?: error("The social state count script returned no reply.")
        if (counted != 1L) {
            throw SocialStatesFullException(
                "$maxStates social sign-ins are in progress in this deployment: this one is not started."
            )
        }
        val stored = redisTemplate.opsForValue().set(keyPrefix + state, bindingHash, lifetime).toFuture().await()
        check(stored == true) { "Redis did not store the social sign-in state." }
    }

    override suspend fun take(state: String): String? =
        redisTemplate.opsForValue().getAndDelete(keyPrefix + state).toFuture().await()

    @InternalCoSecApi
    companion object {
        /**
         * What one sign-in in progress holds in Redis, its two keys together (JustAuth's `oauth:state` and CoSec's
         * `oauth:binding`), measured with `MEMORY USAGE` (`SocialStateBytesTest`: 352 bytes): the bound is counted in it.
         */
        const val STATE_BYTES: Long = 512

        /** A bound for a store built without one: tests and an application's own wiring. */
        const val DEFAULT_MAX_STATES: Long = 100_000

        /** Appended to the store's key prefix to name the count's key: `<s>:oauth:binding:!count`. */
        const val COUNT_SUFFIX = "!count"

        private const val SCRIPT_PATH = "social-states.lua"

        private val SCRIPT: DefaultRedisScript<Long> = DefaultRedisScript(
            checkNotNull(RedisSocialStateStore::class.java.getResourceAsStream(SCRIPT_PATH)) {
                "The social state count script [$SCRIPT_PATH] is not on the classpath."
            }.bufferedReader().use { it.readText() },
            Long::class.java,
        )
    }
}
