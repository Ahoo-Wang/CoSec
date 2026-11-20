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
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.Duration

/**
 * The [SocialStateStore] on Redis: `<keyPrefix><state>` holds the binding hash for the state's lifetime, and [take] is
 * `GETDEL` (Redis 6.2), so a state is taken once whichever instance receives its callback. It runs through the reactive
 * client and awaits it, as [RedisApiKeyReader] does: the caller's thread, which may be an event loop, never waits for
 * Redis, and an anonymous flood of sign-in starts holds no thread. A Redis failure throws, so the sign-in fails rather
 * than proceed unchecked (`IDN-20`); a state a cancelled [take] removed is spent, which fails that sign-in.
 *
 * A state is written with the lifetime it is issued for, so none outlives its sign-in.
 */
class RedisSocialStateStore @InternalCoSecApi constructor(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val keyPrefix: String,
) : SocialStateStore {
    override suspend fun put(state: String, bindingHash: String, lifetime: Duration) {
        val stored = redisTemplate.opsForValue().set(keyPrefix + state, bindingHash, lifetime).toFuture().await()
        check(stored == true) { "Redis did not store the social sign-in state." }
    }

    override suspend fun take(state: String): String? =
        redisTemplate.opsForValue().getAndDelete(keyPrefix + state).toFuture().await()
}
