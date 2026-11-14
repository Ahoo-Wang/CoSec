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

package me.ahoo.cosec.social.justauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import me.ahoo.cosec.social.SocialStateStore
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

/**
 * The [SocialStateStore] on Redis: `<keyPrefix><state>` holds the binding hash for the state's lifetime, and [take] is
 * `GETDEL` (Redis 6.2), so a state is taken once whichever instance receives its callback. A Redis failure throws.
 *
 * [StringRedisTemplate] blocks, and `cosec-social` has no reactive client (no Reactor outside the edge and the host,
 * `SourceRulesTest`), so this store makes its calls on a dispatcher of its own, of at most [parallelism] threads: they
 * never hold the caller's thread, and a cancelled caller interrupts its call. A state a cancelled [take] removed is
 * spent: the sign-in fails, as it should.
 */
class RedisSocialStateStore(
    private val redisTemplate: StringRedisTemplate,
    private val keyPrefix: String,
    parallelism: Int = DEFAULT_PARALLELISM,
) : SocialStateStore {
    private val blocking = Dispatchers.IO.limitedParallelism(parallelism)

    override suspend fun put(state: String, bindingHash: String, lifetime: Duration) = runInterruptible(blocking) {
        redisTemplate.opsForValue().set(keyPrefix + state, bindingHash, lifetime)
    }

    override suspend fun take(state: String): String? = runInterruptible(blocking) {
        redisTemplate.opsForValue().getAndDelete(keyPrefix + state)
    }

    private companion object {
        const val DEFAULT_PARALLELISM = 16
    }
}
