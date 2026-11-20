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

package me.ahoo.cosec.cocache.control

import io.lettuce.core.RedisURI
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.redis.RedisBackendOptions
import me.ahoo.cocache.redis.RedisChannelMode
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.CoSecTestCaches
import me.ahoo.cosec.cocache.createLocked
import me.ahoo.cosec.control.store.ReferrerEntry
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * `CTL-42`: every read of a set of record asks Redis, even within the millisecond its local copy was filled in
 * ([CoSecCacheSpecs.SET_LOCAL_MAX_AGE] says why). The reader fills its local copy with a read; a second instance adds a
 * member over sharded channels, whose invalidation the reader, subscribed to the plain channel, never receives; the
 * reader reads again at once. Thousands of times: a local copy served within its millisecond would show as a failure
 * rate. A set kept locally for a minute, in the same loop, misses the member: the loop can fail.
 */
class SetOfRecordReadTest {
    private fun silentWriter(keyPrefix: String) = CoSecTestCaches(
        RedisBackend.standalone(
            RedisURI.create(REDIS),
            RedisBackendOptions(channelMode = RedisChannelMode.SHARDED),
        ),
        keyPrefix,
    )

    /** How many of [ITERATIONS] reads right after a member was added missed it. */
    private fun <K : Any> stale(reader: SetStore<K, String>, writer: SetStore<K, String>, key: K): Int = runBlocking {
        var stale = 0
        repeat(ITERATIONS) { i ->
            reader.suspending().get(key)
            val member = "m$i"
            writer.suspending().add(key, listOf(member))
            if (member !in reader.suspending().get(key)) stale++
            writer.suspending().remove(key, listOf(member))
        }
        stale
    }

    @Test
    fun `every read of a back-reference set asks Redis, even in the millisecond its copy was filled in`() {
        CoSecTestCaches(CoSecTestCaches.redisBackend()).use { reader ->
            silentWriter(reader.keyPrefix).use { writer ->
                val read = reader.factory.createLocked(ControlCacheSpecs.referenceIndex(reader.keyPrefix)).suspending()
                val write = writer.factory.createLocked(ControlCacheSpecs.referenceIndex(reader.keyPrefix)).suspending()
                val key = ReferenceKey(SnapshotKey.Platform, PolicyId("shared"))
                var stale = 0
                runBlocking {
                    repeat(ITERATIONS) { i ->
                        read.get(key)
                        val entry = ReferrerEntry(SnapshotKey.Tenant("t$i"), 1)
                        write.add(key, listOf(entry))
                        if (entry !in read.get(key)) stale++
                        write.remove(key, listOf(entry))
                    }
                }
                stale.assert().describedAs("$stale of $ITERATIONS reads served a local copy").isZero()
                val admin = checkNotNull(reader.runtime.admin(CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME))
                admin.metrics.localHits.assert().isZero()
            }
        }
    }

    @Test
    fun `the same loop over a set kept locally misses the member, so the test above can fail`() {
        CoSecTestCaches(CoSecTestCaches.redisBackend()).use { reader ->
            silentWriter(reader.keyPrefix).use { writer ->
                val spec = SetStoreSpec.builder<String, String>("ProbeSetCache")
                    .keyPrefix("${reader.keyPrefix}:")
                    .namespace("probe")
                    .keyEncoder(KeyEncoder { it })
                    .elementCodec(StringCodec)
                    .policy { localMaxAge = Duration.ofMinutes(1) }
                    .build()
                stale(reader.factory.create(spec), writer.factory.create(spec), "k").assert().isGreaterThan(0)
            }
        }
    }

    private companion object {
        const val ITERATIONS = 5_000
        const val REDIS = "redis://localhost:6379"
    }
}
