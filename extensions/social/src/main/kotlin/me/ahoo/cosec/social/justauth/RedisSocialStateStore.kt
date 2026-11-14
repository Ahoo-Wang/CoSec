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

import me.ahoo.cosec.social.SocialStateStore
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

/**
 * The [SocialStateStore] on Redis: `<keyPrefix><state>` holds the binding hash for the state's lifetime, and [take] is
 * `GETDEL` (Redis 6.2), so a state is taken once whichever instance receives its callback. A Redis failure throws.
 */
class RedisSocialStateStore(
    private val redisTemplate: StringRedisTemplate,
    private val keyPrefix: String,
) : SocialStateStore {
    override fun put(state: String, bindingHash: String, lifetime: Duration) {
        redisTemplate.opsForValue().set(keyPrefix + state, bindingHash, lifetime)
    }

    override fun take(state: String): String? = redisTemplate.opsForValue().getAndDelete(keyPrefix + state)
}
