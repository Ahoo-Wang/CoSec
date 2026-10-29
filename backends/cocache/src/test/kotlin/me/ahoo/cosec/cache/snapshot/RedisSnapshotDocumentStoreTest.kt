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

package me.ahoo.cosec.cache.snapshot

import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * [RedisSnapshotDocumentStore] on real Redis, Valkey and a Redis Cluster: reads, conditional reads, the invalidation
 * subscription, and how a stored value that cannot be decoded is reported (RFC 0003 §6 F3).
 */
class RedisSnapshotDocumentStoreTest {
    private fun SnapshotRead.revision(): Long = (this as SnapshotRead.Present).document.revision

    private fun Throwable.chain(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    private fun failureOf(future: CompletableFuture<*>): Throwable {
        val error = runCatching { future.get() }.exceptionOrNull()
        check(error is ExecutionException) { "Expected a failed read, got $error." }
        return checkNotNull(error.cause)
    }

    @ParameterizedTest
    @EnumSource(RedisTarget::class)
    fun readsPresentAndAbsentDocumentsWithCoCachesDigest(target: RedisTarget) {
        target.backend().use { backend ->
            SnapshotNode(backend, newKeyPrefix()).use { node ->
                node.store.read(SnapshotKey.Platform).get().assert().isEqualTo(SnapshotRead.Absent)
                node.store.readIfChanged(SnapshotKey.Platform, null).get().assert().isEqualTo(SnapshotRead.Absent)

                node.writer.publish(SnapshotKey.Platform).block()
                // the first read misses the local copy, the second hits it: both carry the digest Redis computes
                val cold = node.store.read(SnapshotKey.Platform).get() as SnapshotRead.Present
                val local = node.store.read(SnapshotKey.Platform).get() as SnapshotRead.Present
                val fromRedis = node.store.readIfChanged(SnapshotKey.Platform, null).get() as SnapshotRead.Present
                cold.document.revision.assert().isEqualTo(1L)
                local.digest.assert().isEqualTo(cold.digest)
                fromRedis.digest.assert().isEqualTo(cold.digest)
                RedisSnapshotDocumentStore.digestOf(cold.document).assert().isEqualTo(cold.digest)
                node.store.readIfChanged(
                    SnapshotKey.Platform,
                    cold.digest
                ).get().assert().isEqualTo(SnapshotRead.Unchanged)

                node.writer.publish(SnapshotKey.Platform).block()
                node.store.readIfChanged(SnapshotKey.Platform, cold.digest).get().revision().assert().isEqualTo(2L)
                node.store.read(SnapshotKey.Platform).get().revision().assert().isEqualTo(2L)
                node.store.localCopyMaxAge.assert().isEqualTo(node.cache.localCopyMaxAge)
            }
        }
    }

    /**
     * Review requirement: CoCache caches "no value" locally for a cache without a loader. A local absence from before
     * a publish must not answer a cold read, or a fresh source would install "no policies" for policies that exist.
     */
    @ParameterizedTest
    @EnumSource(RedisTarget::class)
    fun neverAnswersAbsentFromALocalCopy(target: RedisTarget) {
        val keyPrefix = newKeyPrefix()
        target.backend().use { backend ->
            // The reader never hears of the write, so its local "no value" stays.
            SnapshotNode(SilentChannelBackend(backend), keyPrefix).use { reader ->
                SnapshotNode(backend, keyPrefix).use { writer ->
                    val key = SnapshotKey.App("app")
                    reader.store.read(key).get().assert().isEqualTo(SnapshotRead.Absent)
                    writer.writer.publish(SnapshotKey.App("app")).block()

                    // the local copy still says absent: without the confirmation, read() would too
                    (reader.cache.async().lookup(key).get() is Lookup.Absent).assert().isTrue()
                    reader.store.read(key).get().revision().assert().isEqualTo(1L)
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(RedisTarget::class)
    fun reportsAnUndecodableDocumentAsAFormatError(target: RedisTarget) {
        val keyPrefix = newKeyPrefix()
        target.backend().use { backend ->
            SnapshotNode(backend, keyPrefix).use { node ->
                RawSnapshotWriter(backend, keyPrefix).use { raw ->
                    // CoCache's format, but not a document: a repeated header field
                    raw.put(
                        SnapshotKey.Platform,
                        """{"schema":2,"revision":1,"revision":2,"publishedAt":"2026-10-21T08:00:00Z"}"""
                    )
                }
                listOf(
                    node.store.read(SnapshotKey.Platform),
                    node.store.readIfChanged(SnapshotKey.Platform, null),
                ).forEach { read ->
                    failureOf(read).chain().any { it is SnapshotFormatException }.assert().isTrue()
                }

                // not CoCache's format at all (format 0), such as a value another program wrote
                val key = SnapshotKey.App("app")
                target.rawSet(node.cache.storageKey(key), "plain".encodeToByteArray())
                listOf(node.store.read(key), node.store.readIfChanged(key, null)).forEach { read ->
                    val error = failureOf(read)
                    (error is SnapshotFormatException).assert().isTrue()
                    error.message.assert().contains("app:app")
                }
            }
        }
    }

    @Test
    fun reportsAnUnreachableStoreAsAReadFailure() {
        val backend = HookedBackend(InMemoryBackend())
        SnapshotNode(backend, newKeyPrefix()).use { node ->
            backend.failAll = true
            listOf(
                node.store.read(SnapshotKey.Platform),
                node.store.readIfChanged(SnapshotKey.Platform, null),
            ).forEach { read ->
                failureOf(read).chain().none { it is SnapshotFormatException }.assert().isTrue()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(RedisTarget::class)
    fun passesOnInvalidationsOfItsNamespaceFromEveryInstance(target: RedisTarget) {
        val keyPrefix = newKeyPrefix()
        target.backend().use { backend ->
            SnapshotNode(backend, keyPrefix).use { listening ->
                SnapshotNode(backend, keyPrefix).use { writing ->
                    val changed = CopyOnWriteArrayList<SnapshotKey>()
                    val subscription = listening.store.subscribe(object : SnapshotChangeListener {
                        override fun onChanged(keys: Collection<SnapshotKey>) {
                            changed.addAll(keys)
                        }

                        override fun onReset() = Unit
                    })
                    writing.writer.publish(SnapshotKey.Platform).block()
                    writing.writer.publish(SnapshotKey.App("app")).block()
                    awaitTrue(what = "both invalidations") {
                        changed.containsAll(listOf(SnapshotKey.Platform, SnapshotKey.App("app")))
                    }
                    // an instance hears of its own writes too
                    listening.writer.publish(SnapshotKey.Platform).block()
                    awaitTrue(what = "the instance's own write") { changed.count { it == SnapshotKey.Platform } >= 2 }

                    subscription.close()
                    changed.clear()
                    writing.writer.publish(SnapshotKey.Platform).block()
                    listening.writer.publish(SnapshotKey.Platform).block()
                    Thread.sleep(200)
                    changed.assert().isEmpty()
                }
            }
        }
    }

    @Test
    fun mapsOnlyItsOwnStorageKeys() {
        SnapshotNode(InMemoryBackend(), "k").use { node ->
            node.cache.snapshotKeyOf("k:snapshot:platform").assert().isEqualTo(SnapshotKey.Platform)
            node.cache.snapshotKeyOf("k:snapshot:app:a").assert().isEqualTo(SnapshotKey.App("a"))
            node.cache.snapshotKeyOf("k:snapshot:other").assert().isNull()
            node.cache.snapshotKeyOf("k:policy:p").assert().isNull()
            node.cache.storageKey(SnapshotKey.App("a")).assert().isEqualTo("k:snapshot:app:a")
        }
    }

    @Test
    fun aFailingListenerDoesNotStopTheOthers() {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val heard = CopyOnWriteArrayList<String>()
            node.store.subscribe(object : SnapshotChangeListener {
                override fun onChanged(keys: Collection<SnapshotKey>) = throw IllegalStateException("broken listener")

                override fun onReset() = throw IllegalStateException("broken listener")
            })
            node.store.subscribe(object : SnapshotChangeListener {
                override fun onChanged(keys: Collection<SnapshotKey>) {
                    heard += "changed"
                }

                override fun onReset() {
                    heard += "reset"
                }
            })
            node.store.notifyChanged(SnapshotKey.Platform)
            heard.assert().contains("changed")
        }
    }

    /** Review (M12): a reset of the channel reaches the store's listeners. */
    @Test
    fun forwardsResets() {
        val backend = ReversingChannelBackend(InMemoryBackend())
        SnapshotNode(backend, newKeyPrefix()).use { node ->
            val resets = AtomicInteger()
            node.store.subscribe(object : SnapshotChangeListener {
                override fun onChanged(keys: Collection<SnapshotKey>) = Unit

                override fun onReset() {
                    resets.incrementAndGet()
                }
            }).use {
                val before = resets.get()
                backend.reset(node.cache.namespace)
                resets.get().assert().isEqualTo(before + 1)
            }
            backend.reset(node.cache.namespace)
        }
    }

    /** Review (M16): the digest of a local copy, which grows with the document, is computed off the caller's thread. */
    @Test
    fun computesTheDigestOfALocalCopyOffTheCallersThread() {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            node.writer.publish(SnapshotKey.Platform).block()
            val ran = CopyOnWriteArrayList<String>()
            val executor = Executor { task ->
                Thread({
                    ran += Thread.currentThread().name
                    task.run()
                }, "digest-test").start()
            }
            val store = RedisSnapshotDocumentStore(node.cache, executor)
            store.read(SnapshotKey.Platform).get()
            ran.clear()
            // now a local hit: answered without Redis, but hashed on the executor
            val read = store.read(SnapshotKey.Platform).get() as SnapshotRead.Present
            ran.assert().containsExactly("digest-test")
            read.digest.assert().isEqualTo(RedisSnapshotDocumentStore.digestOf(read.document))
        }
    }

    /** Review (M19): an unconditional read answered "unchanged" is a CoCache bug, never taken for absent. */
    @Test
    fun anUnconditionalReadAnsweredUnchangedFails() {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val async = object : AsyncCache<SnapshotKey, SnapshotDocument> by node.cache.async() {
                override fun getIfChanged(
                    key: SnapshotKey,
                    known: Digest?
                ): CompletableFuture<ConditionalGet<SnapshotDocument>> = CompletableFuture.completedFuture(
                    ConditionalGet.Unchanged
                )
            }
            val cache = PolicySnapshotCache(
                object : Cache<SnapshotKey, SnapshotDocument> by node.cache {
                    override fun async(): AsyncCache<SnapshotKey, SnapshotDocument> = async
                },
                node.cache.namespace,
                node.cache.localCopyMaxAge,
                node.cache.channel,
            )
            val error = failureOf(RedisSnapshotDocumentStore(cache).read(SnapshotKey.Platform))
            (error is IllegalStateException).assert().isTrue()
        }
    }
}
