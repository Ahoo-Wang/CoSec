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

package me.ahoo.cosec.migrate

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.control.LegacyReferrersCodec
import me.ahoo.cosec.cocache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** Writes what a store of 8.0.0-beta.4 held, as CoCache stores a value, for the conversion to find. */
internal class LegacyStore(uri: String, private val keyPrefix: String) : AutoCloseable {
    private val backend = RedisBackend.standalone(RedisURI.create(uri))
    private val client = RedisClient.create(RedisURI.create(uri))
    private val connection = client.connect()
    private val runtime = CacheRuntime.builder().backend(backend).build()
    private val factory = CoCacheFactory(runtime)

    private object Raw : Codec<ByteArray> {
        override fun encode(value: ByteArray): ByteArray = value

        override fun decode(bytes: ByteArray): ByteArray = bytes
    }

    private fun raw(name: String, cacheName: String) = CoSecCacheSpecs.layout(keyPrefix, cacheName).let { layout ->
        factory.create(
            StoreSpec.builder<String, ByteArray>(name)
                .keyPrefix(layout.keyPrefix)
                .namespace(layout.namespace)
                .keyEncoder(KeyEncoder { it })
                .codec(Raw)
                .build()
        )
    }

    /** The back-references of [target]'s [policy] as one value, in place of the set a publish of this version wrote. */
    fun references(target: SnapshotKey, policy: PolicyId, referrers: Map<SnapshotKey, Long>) {
        val encoded = "${SnapshotKeyEncoder.encode(target)}:${policy.value}"
        val path = CoSecCacheSpecs.storagePath(keyPrefix, CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME)
        connection.sync().del("$path:$encoded")
        raw("LegacyReferences", CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME)
            .put(encoded, LegacyReferrersCodec.encode(referrers))
    }

    /** The document list under the lists, each entry listed at the epoch. */
    fun documents(vararg entries: String) {
        val body = entries.sorted().joinToString(",", "{", "}") { "\"$it\":\"1970-01-01T00:00:00Z\"" }
        raw("LegacyLists", CoSecCacheSpecs.CONTROL_LIST_CACHE_NAME).put("documents", body.encodeToByteArray())
    }

    override fun close() {
        runtime.close()
        backend.close()
        connection.close()
        client.shutdown()
    }
}
