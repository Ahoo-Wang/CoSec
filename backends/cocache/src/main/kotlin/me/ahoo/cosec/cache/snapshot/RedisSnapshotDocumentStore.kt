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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * The [SnapshotDocumentStore] over CoCache and Redis (RFC 0003 §4.1–§4.4).
 *
 * - [read] may answer from this instance's local (L2) copy, at most [localCopyMaxAge] old. It never answers
 *   [SnapshotRead.Absent] from a local copy: CoCache caches "no value" locally for a cache without a loader, and an
 *   absence remembered from before a publish would install "no policies" for documents that exist. A local miss of
 *   any kind is confirmed with a conditional read of Redis.
 * - [readIfChanged] is CoCache's conditional read of Redis, which bypasses every local copy.
 * - [subscribe] listens to CoCache's invalidation channel of the cache's namespace (RFC 0003 Q6). The cache subscribed
 *   when it was created, so its engine has dropped its local copy before a listener here is told (RFC 0003 §4.4,
 *   guarded by a test); every invalidation is passed on, whichever instance wrote it.
 *
 * Nothing here blocks: Redis is read through CoCache's async API, and the digest of a local copy, which CoCache does
 * not keep, is computed on [digestExecutor] rather than the caller's thread. A stored value that cannot be decoded fails
 * the read with a [SnapshotFormatException] in its cause chain; any other failure is the read's own.
 */
class RedisSnapshotDocumentStore(
    private val cache: PolicySnapshotCache,
    private val digestExecutor: Executor = ForkJoinPool.commonPool(),
) : SnapshotDocumentStore {
    private val async = cache.async()
    private val listeners = CopyOnWriteArrayList<SnapshotChangeListener>()

    override val localCopyMaxAge: Duration get() = cache.localCopyMaxAge

    override fun read(key: SnapshotKey): CompletableFuture<SnapshotRead> = guard(key) {
        async.lookup(key).thenCompose { lookup ->
            when (lookup) {
                is Lookup.Present -> present(lookup.value)
                // Possibly a local "no value" from before a publish: only Redis may answer absent.
                is Lookup.Absent -> async.getIfChanged(key, null).thenApply { unconditional(key, it) }
            }
        }
    }

    override fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?): CompletableFuture<SnapshotRead> = guard(key) {
        async.getIfChanged(key, known?.let { Digest(it.toByteArray()) }).thenApply { toRead(it) }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun subscribe(listener: SnapshotChangeListener): AutoCloseable {
        listeners.add(listener)
        val subscription = try {
            cache.channel.subscribe(cache.namespace, ChannelListener(listener))
        } catch (error: RuntimeException) {
            listeners.remove(listener)
            throw error
        }
        return AutoCloseable {
            listeners.remove(listener)
            subscription.close()
        }
    }

    /**
     * Tells this instance's listeners that [key] changed, without waiting for the invalidation to come back from Redis:
     * a publisher calls it after a write, so that the writer reads its own write at once (RFC 0003 §4.6).
     */
    fun notifyChanged(key: SnapshotKey) {
        val keys = listOf(key)
        listeners.forEach { listener -> deliver("changed") { listener.onChanged(keys) } }
    }

    /** A local copy carries no digest: SHA-1 of the stored bytes, as CoCache computes it, off the caller's thread. */
    private fun present(document: SnapshotDocument): CompletableFuture<SnapshotRead> =
        CompletableFuture.supplyAsync({ SnapshotRead.Present(document, digestOf(document)) }, digestExecutor)

    private fun toRead(read: ConditionalGet<SnapshotDocument>): SnapshotRead = when (read) {
        ConditionalGet.Unchanged -> SnapshotRead.Unchanged
        ConditionalGet.Absent -> SnapshotRead.Absent
        is ConditionalGet.Present -> SnapshotRead.Present(read.value, SnapshotDigest(read.digest.sha1))
    }

    /** A conditional read without a known digest, which CoCache never answers with "unchanged". */
    private fun unconditional(key: SnapshotKey, read: ConditionalGet<SnapshotDocument>): SnapshotRead {
        check(read != ConditionalGet.Unchanged) { "An unconditional read of [$key] was answered as unchanged." }
        return toRead(read)
    }

    /**
     * Never throws: a synchronous failure and every exceptional completion complete the returned future with
     * [failure], unwrapped, as CoCache's async API does.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun guard(
        key: SnapshotKey,
        read: () -> CompletableFuture<SnapshotRead>,
    ): CompletableFuture<SnapshotRead> {
        val future = try {
            read()
        } catch (error: Exception) {
            CompletableFuture.failedFuture(error)
        }
        val result = CompletableFuture<SnapshotRead>()
        future.whenComplete { value, error ->
            if (error != null) {
                result.completeExceptionally(failure(key, error))
            } else {
                result.complete(value)
            }
        }
        return result
    }

    private inner class ChannelListener(private val listener: SnapshotChangeListener) : InvalidationListener {
        override fun onInvalidated(keys: List<String>, origin: NodeId) {
            val changed = keys.mapNotNull { cache.snapshotKeyOf(it) }
            if (changed.isNotEmpty()) {
                deliver("changed") { listener.onChanged(changed) }
            }
        }

        override fun onReset() {
            deliver("reset") { listener.onReset() }
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}

        /** The digest CoCache computes for the stored bytes of [document]: the codec writes the body unchanged. */
        fun digestOf(document: SnapshotDocument): SnapshotDigest =
            SnapshotDigest(Digest.of(Envelope.Scalar(document.bodyBytes())).sha1)

        /**
         * [error] as the SPI expects it: unwrapped, and with a [SnapshotFormatException] in its cause chain when the
         * stored value exists but cannot be decoded (CoCache reports it with a [ForeignFormatException]).
         */
        internal fun failure(key: SnapshotKey, error: Throwable): Throwable {
            val cause = if (error is CompletionException && error.cause != null) error.cause!! else error
            val chain = generateSequence(cause) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
            if (chain.any { it is SnapshotFormatException } || chain.none { it is ForeignFormatException }) {
                return cause
            }
            return SnapshotFormatException("Snapshot document [$key] cannot be decoded.", cause)
        }

        private const val MAX_CAUSE_DEPTH = 16

        /** A listener must not break the channel's delivery to the others; only a VM error goes through. */
        @Suppress("TooGenericExceptionCaught")
        private inline fun deliver(what: String, block: () -> Unit) {
            try {
                block()
            } catch (error: VirtualMachineError) {
                throw error
            } catch (error: Throwable) {
                log.error(error) { "A snapshot change listener failed on [$what]." }
            }
        }
    }
}
