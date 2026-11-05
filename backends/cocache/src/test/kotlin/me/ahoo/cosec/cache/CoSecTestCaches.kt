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

import io.lettuce.core.RedisURI
import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.Spec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.id.RandomIdGenerator

/**
 * One CoSec instance: its own [CacheRuntime] (local caches) over a [backend] that other instances may share.
 * Real CoCache engines throughout, never mocks.
 */
internal class CoSecTestCaches(
    backend: CoherenceBackend = InMemoryBackend(),
    val keyPrefix: String = newKeyPrefix(),
) : AutoCloseable {
    val runtime: CacheRuntime = CacheRuntime.builder().backend(backend).build()

    /** Creates caches as the starter does, with CoSec's locks, but without the starter's configuration layers. */
    val factory: CoCacheFactory = CoCacheFactory(runtime)

    val revokedTokenCache: RevokedTokenCache by lazy {
        factory.createCache(CoSecCacheSpecs.revokedToken(CoSecCacheSpecs.jacksonCodec(CoSecJson.mapper), keyPrefix))
    }

    val revokedSubjectCache: RevokedSubjectCache by lazy {
        factory.createCache(
            CoSecCacheSpecs.revokedSubject(CoSecCacheSpecs.jacksonCodec(CoSecJson.mapper), keyPrefix)
        )
    }

    val membershipCache: MembershipCache by lazy { factory.createCache(CoSecCacheSpecs.membership(keyPrefix)) }

    /** Drops this instance's local copies only; the shared backend keeps the values. */
    fun clearLocal(cacheName: String) {
        checkNotNull(runtime.admin(cacheName)).clearLocal()
    }

    override fun close() {
        runtime.close()
    }

    companion object {
        fun newKeyPrefix(): String = "test:" + RandomIdGenerator.generate()

        /** The Redis the tests run against (`localhost:6379`). */
        fun redisBackend(): RedisBackend = RedisBackend.standalone(RedisURI.create("redis://localhost:6379"))
    }
}

/**
 * Creates the cache declared by [spec] with CoSec's locks and exposes it as [C], one of CoSec's cache interfaces.
 */
inline fun <reified C : ReadableCache<*, *>> CoCacheFactory.createCache(spec: Spec<*, *>): C =
    create(C::class.java, spec, CoSecCacheSpecs.locks(spec.name))
