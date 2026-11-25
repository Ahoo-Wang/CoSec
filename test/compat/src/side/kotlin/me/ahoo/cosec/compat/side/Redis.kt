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

import io.lettuce.core.RedisURI
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.createLocked
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * This version's view of the Redis on localhost:6379, as a deployment of it runs: CoCache's backend under its own
 * runtime, and Spring Data Redis's templates. Each operation takes a runtime of its own, so nothing one operation read
 * stays in a local copy another reads from: what one version wrote, the other reads from Redis.
 */
internal object Redis {
    val backend: RedisBackend by lazy { RedisBackend.standalone(RedisURI.create("redis://localhost:6379")) }

    private val connections: LettuceConnectionFactory by lazy {
        LettuceConnectionFactory(RedisStandaloneConfiguration()).apply {
            afterPropertiesSet()
            start()
        }
    }

    val strings: StringRedisTemplate by lazy { StringRedisTemplate(connections) }

    val reactive: ReactiveStringRedisTemplate by lazy { ReactiveStringRedisTemplate(connections) }

    /** [block] over a fresh CoCache runtime, closed after. */
    fun <T> caches(block: (CoCacheFactory) -> T): T {
        val runtime = CacheRuntime.builder().backend(backend).build()
        try {
            return block(CoCacheFactory(runtime))
        } finally {
            runtime.close()
        }
    }

    /**
     * The values of the cache [cacheName] under [prefix], as bytes: the keys and layout CoSec gives that cache, and the
     * envelope this version's CoCache puts around a value, so what is written here reads as written by the cache itself.
     */
    fun raw(factory: CoCacheFactory, cacheName: String, prefix: String): Store<String, ByteArray> {
        val layout = CoSecCacheSpecs.layout(prefix, cacheName)
        val spec = StoreSpec.builder<String, ByteArray>(cacheName)
            .keyPrefix(layout.keyPrefix)
            .namespace(layout.namespace)
            .keyEncoder(KeyEncoder.ofToString())
            .codec(Bytes)
            .build()
        return factory.createLocked(spec)
    }

    private object Bytes : Codec<ByteArray> {
        override fun encode(value: ByteArray): ByteArray = value

        override fun decode(bytes: ByteArray): ByteArray = bytes
    }
}
