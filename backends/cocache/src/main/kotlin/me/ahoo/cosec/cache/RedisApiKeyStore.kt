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

package me.ahoo.cosec.cache

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.apikey.ApiKey
import me.ahoo.cosec.spi.apikey.ApiKeyStore
import me.ahoo.cosec.spi.apikey.ApiKeys
import org.springframework.data.redis.core.StringRedisTemplate

const val DEFAULT_API_KEY_KEY_PREFIX = "cosec:api-key"

/**
 * API keys kept in Redis by hash, shared by every instance: `<keyPrefix>:<sha256>` holds the [ApiKey] as [ApiKeyCodec]
 * writes it.
 *
 * Lookups are Redis round trips, so the security context must be parsed off the event loop (the starter does so
 * when `cosec.api-key.store=redis`). A Redis failure propagates and fails closed.
 */
class RedisApiKeyStore(
    private val stringRedisTemplate: StringRedisTemplate,
    keyPrefix: String = DEFAULT_API_KEY_KEY_PREFIX
) : ApiKeyStore {
    private val keyPrefix = keyPrefix.trimEnd(':')

    companion object {
        /** `<keyPrefix>:<hash>`, with a trailing `:` of [keyPrefix] trimmed. */
        fun redisKey(keyPrefix: String, hash: String): String = "${keyPrefix.trimEnd(':')}:$hash"
    }

    override fun findByHash(hash: String): ApiKey? =
        stringRedisTemplate.opsForValue().get(
            redisKey(hash)
        )?.let(ApiKeyCodec::decode)

    /**
     * Creates a key for [apiKey], stores only its hash, and returns the key. It cannot be recovered afterwards.
     */
    fun issue(apiKey: ApiKey): String {
        val key = ApiKeys.generate()
        save(ApiKeys.hash(key), apiKey)
        return key
    }

    /**
     * Stores [apiKey] under [hash], replacing any key with that hash (e.g. to disable it or change its roles).
     */
    fun save(hash: String, apiKey: ApiKey) {
        stringRedisTemplate.opsForValue().set(redisKey(hash), ApiKeyCodec.encode(apiKey))
    }

    /** Removes the key with [hash]; it stops working on the next request. */
    fun revoke(hash: String): Boolean = stringRedisTemplate.delete(redisKey(hash))

    private fun redisKey(hash: String): String = redisKey(keyPrefix, hash)
}
