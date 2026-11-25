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

package me.ahoo.cosec.social.justauth

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.zhyd.oauth.cache.AuthStateCache
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

/**
 * Redis Auth State Cache .
 *
 * @author ahoo wang
 */
@InternalCoSecApi
class RedisAuthStateCache(
    private val redisTemplate: StringRedisTemplate,
    private val keyPrefix: String = DEFAULT_KEY_PREFIX
) :
    AuthStateCache {

    private fun withPredix(key: String): String {
        return keyPrefix + key
    }

    override fun cache(key: String, value: String) {
        cache(key, value, DEFAULT_TIME_OUT)
    }

    override fun cache(key: String, value: String, timeout: Long) {
        redisTemplate.opsForValue().set(withPredix(key), value, Duration.ofMillis(timeout))
    }

    /** The cached value; empty when there is none, never an exception (round-2 review M4). */
    override fun get(key: String): String {
        return redisTemplate.opsForValue()[withPredix(key)].orEmpty()
    }

    override fun containsKey(key: String): Boolean {
        return redisTemplate.hasKey(withPredix(key))
    }

    @InternalCoSecApi
    companion object {
        private val DEFAULT_TIME_OUT = Duration.ofMinutes(3).toMillis()
        private const val STATE_FAMILY = "oauth:state"
        private const val DEFAULT_KEY_PREFIX = "cosec:$STATE_FAMILY:"

        /**
         * Where a deployment keeps JustAuth's states: `<keyPrefix>:oauth:state:`, followed by the state. Frozen within
         * 8.x (RFC 0014 R6, `SNP-26`): a callback reaching a release that derives it otherwise finds no state.
         */
        fun keyPrefix(cosecKeyPrefix: String): String = "${cosecKeyPrefix.trimEnd(':')}:$STATE_FAMILY:"
    }
}
