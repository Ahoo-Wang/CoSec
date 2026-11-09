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

import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.runtime.snapshot.SnapshotCompiler
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * RFC 0003 §6 F9 on real Redis: CoCache's engine subscribed to the namespace when the cache was created, so it drops
 * its local copy before the store's listener hears of the change, and a read the listener starts sees the new revision.
 * That is an implementation detail of CoCache; when the order is reversed, the read sees the old copy and the periodic
 * revalidation, which bypasses local copies, still installs the new revision, as it does when notifications are lost.
 *
 * None of it may depend on timing. A write invalidates the local copies of every other node, so the write that gives the
 * reader its first revision does too, and an invalidation that arrives after the reader has filled its copy drops it:
 * the test would then read a revision it expects to be stale from Redis. The tests wait for that invalidation, and for
 * the engine to have dealt with it, before they fill the copy ([withCopyOfRevisionOne]), and check that the copy is there.
 * The source under test gets no notification and no periodic pass of its own, so only the revalidation the test asks
 * for can install the new revision: a source that was told, or that revalidated on its own, would pass without it.
 */
class SnapshotInvalidationOrderTest {
    /** Starts a read of [key] whenever the store reports a change of it, from inside the callback. */
    private class ReadingListener(private val node: SnapshotNode) : SnapshotChangeListener {
        val reads = CopyOnWriteArrayList<CompletableFuture<SnapshotRead>>()

        override fun onChanged(keys: Collection<SnapshotKey>) {
            if (SnapshotKey.Platform in keys) {
                reads += node.store.read(SnapshotKey.Platform)
            }
        }

        override fun onReset() = Unit

        fun revisionsRead(): List<Long> = reads.map { (it.get() as SnapshotRead.Present).document.revision }
    }

    /** Counts the changes of the platform document it is told of, and reads nothing. */
    private class CountingListener : SnapshotChangeListener {
        val platformChanges = AtomicInteger()

        override fun onChanged(keys: Collection<SnapshotKey>) {
            if (SnapshotKey.Platform in keys) {
                platformChanges.incrementAndGet()
            }
        }

        override fun onReset() = Unit
    }

    /** [delegate] without notifications: what changed is for the revalidation to find. */
    private class Unnotified(private val delegate: SnapshotDocumentStore) : SnapshotDocumentStore by delegate {
        override fun subscribe(listener: SnapshotChangeListener): AutoCloseable = AutoCloseable { }
    }

    /**
     * A node whose local copy holds revision 1, and another node that publishes revision 2.
     *
     * The reader's copy is filled only once the invalidation of the first write has reached it (and, behind
     * [ReversingChannelBackend], its engine), so no invalidation is on its way when [test] starts, and the copy is
     * checked to be there. A reader behind [SilentChannelBackend] is told of nothing, so there is nothing to wait for.
     */
    private fun withCopyOfRevisionOne(
        target: RedisTarget,
        readerBackend: (CoherenceBackend) -> CoherenceBackend,
        test: (reader: SnapshotNode, writer: SnapshotNode) -> Unit,
    ) {
        val keyPrefix = newKeyPrefix()
        target.backend().use { backend ->
            readerBackend(backend).use { wrapped ->
                SnapshotNode(wrapped, keyPrefix).use { reader ->
                    SnapshotNode(backend, keyPrefix).use { writer ->
                        val first = CountingListener()
                        reader.store.subscribe(first).use {
                            writer.writer.publish(SnapshotKey.Platform).block()
                            if (wrapped !is SilentChannelBackend) {
                                awaitTrue(what = "the invalidation of revision 1") { first.platformChanges.get() == 1 }
                            }
                            if (wrapped is ReversingChannelBackend) {
                                // The listeners were told before the engine: it drops its copy when they have returned.
                                awaitTrue(what = "the engine told of revision 1") { wrapped.dispatched.get() == 1 }
                            }
                        }
                        // fills the reader's local copy with revision 1
                        (reader.store.read(SnapshotKey.Platform).get() as SnapshotRead.Present).document.revision
                            .assert().isEqualTo(1L)
                        (reader.cache.async().lookup(SnapshotKey.Platform).get() as Lookup.Present).value.revision
                            .assert().describedAs("the local copy the test starts from").isEqualTo(1L)
                        test(reader, writer)
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(RedisTarget::class)
    fun theEngineDropsItsCopyBeforeTheListenerHears(target: RedisTarget) {
        withCopyOfRevisionOne(target, { NotClosing(it) }) { reader, writer ->
            val listener = ReadingListener(reader)
            reader.store.subscribe(listener).use {
                writer.writer.publish(SnapshotKey.Platform).block()
                awaitTrue(what = "the invalidation") { listener.reads.isNotEmpty() }
                // the copy was there, and the listener's read did not find it
                listener.revisionsRead().first().assert().isEqualTo(2L)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(RedisTarget::class)
    fun revalidationConvergesWhenTheListenerHearsFirst(target: RedisTarget) {
        withCopyOfRevisionOne(target, ::ReversingChannelBackend) { reader, writer ->
            val listener = ReadingListener(reader)
            DocumentSnapshots.source(Unnotified(reader.store), compileToRevision, quietPasses).use { source ->
                reader.store.subscribe(listener).use {
                    source.load().get().version.shared.assert().isEqualTo(1L)
                    writer.writer.publish(SnapshotKey.Platform).block()
                    awaitTrue(what = "the invalidation") { listener.reads.isNotEmpty() }
                    // the listener heard first: its read was answered by the stale local copy
                    listener.revisionsRead().first().assert().isEqualTo(1L)
                    // the source was told of nothing, and no pass of its own is due
                    source.peek()!!.version.shared.assert().isEqualTo(1L)
                    source.revalidate().get()
                    source.peek()!!.version.shared.assert().isEqualTo(2L)
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(RedisTarget::class)
    fun revalidationConvergesWhenNotificationsAreLost(target: RedisTarget) {
        withCopyOfRevisionOne(target, ::SilentChannelBackend) { reader, writer ->
            DocumentSnapshots.source(reader.store, compileToRevision, quietPasses).use { source ->
                source.load().get().version.shared.assert().isEqualTo(1L)
                writer.writer.publish(SnapshotKey.Platform).block()
                // nothing told the source, and no pass of its own is due
                source.peek()!!.version.shared.assert().isEqualTo(1L)
                source.revalidate().get()
                source.peek()!!.version.shared.assert().isEqualTo(2L)
                // nor the reader: its local copy still holds revision 1
                (reader.cache.async().lookup(SnapshotKey.Platform).get() as Lookup.Present).value.revision
                    .assert().isEqualTo(1L)
            }
        }
    }

    /** The backend itself, closed by its owner. */
    private class NotClosing(delegate: CoherenceBackend) : CoherenceBackend by delegate {
        override fun close() = Unit
    }

    private companion object {
        val compileToRevision = SnapshotCompiler { _, document -> document.revision }

        /** The periodic pass is a minute away (less than `lagWarn`): a test finishes long before it. */
        val quietPasses = SnapshotOptions(revalidateInterval = Duration.ofSeconds(55))
    }
}
