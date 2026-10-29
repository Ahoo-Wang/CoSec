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

package me.ahoo.cosec.cache

import kotlinx.coroutines.future.await
import me.ahoo.cosec.spi.apikey.ApiKey
import me.ahoo.cosec.spi.apikey.ApiKeyReader
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

/**
 * The [ApiKeyReader] over the keys [RedisApiKeyStore] writes, for the coroutine pipeline (RFC 0006 §5.3.2): it reads
 * through the reactive client, so a lookup never blocks the calling thread, which may be an event loop. A Redis failure
 * throws, so the request fails closed with 503; an unknown key is null.
 */
class RedisApiKeyReader(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val keyPrefix: String = DEFAULT_API_KEY_KEY_PREFIX,
) : ApiKeyReader {
    override suspend fun findByHash(hash: String): ApiKey? =
        redisTemplate.opsForValue().get(RedisApiKeyStore.redisKey(keyPrefix, hash)).toFuture().await()
            ?.let(ApiKeyCodec::decode)
}
