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
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.codec.ByteArrayCodec
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.DEFAULT_KEY_PREFIX
import me.ahoo.cosec.cocache.control.CoCacheControlStore
import me.ahoo.cosec.cocache.snapshot.createPolicySnapshotCache

/** The Redis the commands read the 7.x data from and write the 8.0 control store to, imported by each command. */
internal object RedisAccess {
    private const val SCAN_COUNT = 1000L

    /** The 7.x data under [keyPrefix], with its schema marker, read with the same layout as 7.0. */
    fun readRedis(uri: String, keyPrefix: String): Source7x {
        val redisUri = RedisURI.create(uri)
        val client = RedisClient.create(redisUri)
        try {
            client.connect().use { connection ->
                val commands = connection.sync()
                val markerKey = "${keyPrefix.trimEnd(':')}:meta:schema"
                val marker = Source7x.Marker(markerKey, commands.get(markerKey))
                val keys = CoSec7xReader.keys(keyPrefix) { pattern -> scan(commands, pattern) }
                return client.connect(ByteArrayCodec.INSTANCE).use { raw ->
                    CoSec7xReader(raw.sync(), keyPrefix).source(keys, marker)
                }
            }
        } finally {
            client.shutdown()
        }
    }

    fun scan(commands: RedisCommands<String, String>, pattern: String): Sequence<String> {
        val keys = ArrayList<String>()
        var cursor: ScanCursor = ScanCursor.INITIAL
        do {
            val result = commands.scan(cursor, ScanArgs.Builder.matches(pattern).limit(SCAN_COUNT))
            keys += result.keys
            cursor = result
        } while (!result.isFinished)
        return keys.asSequence()
    }

    /** The control plane's store over the Redis of `--to-redis` (seed nodes of a cluster with `--cluster`), for [block]. */
    fun <T> controlStore(options: Options, block: (CoCacheControlStore) -> T): T =
        controlStore(
            options.required("to-redis"),
            options.value("key-prefix") ?: DEFAULT_KEY_PREFIX,
            options.flag("cluster"),
            block
        )

    /** The control plane's store over the Redis at [uri], or the Redis Cluster [uri] names seed nodes of, for [block]. */
    fun <T> controlStore(
        uri: String,
        keyPrefix: String,
        cluster: Boolean = false,
        block: (CoCacheControlStore) -> T
    ): T = factory(uri, cluster) { factory ->
        val heads = factory.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(keyPrefix))
        block(CoCacheControlStore.create(factory, heads, keyPrefix))
    }

    /** CoCache 8 over the Redis at [uri], or the Redis Cluster [uri] names seed nodes of, for [block]. */
    private fun <T> factory(uri: String, cluster: Boolean, block: (CoCacheFactory) -> T): T {
        val backend = if (cluster) {
            RedisBackend.cluster(uri.split(',').map { RedisURI.create(it.trim()) })
        } else {
            RedisBackend.standalone(RedisURI.create(uri))
        }
        return backend.use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime -> block(CoCacheFactory(runtime)) }
        }
    }
}
