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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier

/** A clock the test moves by hand. */
class ManualClock(@Volatile var now: Instant = Instant.parse("2026-10-21T08:00:00Z")) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

/** What [FakeCompiler] produces: the revision, the entries, and which entries failed to compile (RFC 0003 F4). */
data class Compiled(val key: SnapshotKey, val revision: Long, val entries: List<String>, val broken: Set<String>)

/**
 * Compiles a body of comma-separated entries. An entry starting with `!` is broken; a body of `THROW` fails the
 * whole document. [gate], when set, holds every compilation until it opens.
 */
class FakeCompiler : SnapshotCompiler<Compiled> {
    val threads: MutableList<Thread> = CopyOnWriteArrayList()
    val compiled: MutableList<Pair<SnapshotKey, Long>> = CopyOnWriteArrayList()

    @Volatile
    var gate: CountDownLatch? = null

    /** When set, every compilation throws it, whatever the body. */
    @Volatile
    var failure: Throwable? = null

    override fun compile(key: SnapshotKey, document: SnapshotDocument): Compiled {
        threads.add(Thread.currentThread())
        gate?.await(10, TimeUnit.SECONDS)
        compiled.add(key to document.revision)
        failure?.let { throw it }
        val body = document.bodyBytes().decodeToString()
        require(body != "THROW") { "unknown matcher type" }
        val entries = if (body.isEmpty()) emptyList() else body.split(',')
        return Compiled(
            key,
            document.revision,
            entries.map { it.removePrefix("!") },
            entries.filter { it.startsWith("!") }.map { it.removePrefix("!") }.toSet()
        )
    }
}

fun document(revision: Long, body: String = "p$revision"): SnapshotDocument =
    SnapshotDocument(
        SnapshotDocument.DOCUMENT_SCHEMA,
        revision,
        Instant.parse("2026-10-21T08:00:00Z"),
        body.encodeToByteArray()
    )

/**
 * A [SnapshotDocumentStore] the test drives: it can fail reads, fail them as undecodable, hold them until released,
 * drop notifications, and it counts every call. The answer of a held read is fixed when the read starts.
 */
class ControllableStore : SnapshotDocumentStore {
    private class Stored(val document: SnapshotDocument, val digest: SnapshotDigest)

    inner class PendingRead(val key: SnapshotKey, val sequence: Int, val answer: CompletableFuture<SnapshotRead>, val future: CompletableFuture<SnapshotRead>) {
        fun release() {
            answer.whenComplete { read, error ->
                if (error != null) {
                    future.completeExceptionally(
                        error
                    )
                } else {
                    future.complete(read)
                }
            }
        }
    }

    private val documents = ConcurrentHashMap<SnapshotKey, Stored>()
    private val listeners = CopyOnWriteArrayList<SnapshotChangeListener>()
    private val sequence = AtomicInteger()

    val reads = AtomicInteger()
    val conditionalReads = AtomicInteger()
    val pending: MutableList<PendingRead> = CopyOnWriteArrayList()

    @Volatile
    var failure: Throwable? = null

    @Volatile
    var undecodable: Boolean = false

    @Volatile
    var hold: Boolean = false

    @Volatile
    var dropNotifications: Boolean = false

    fun put(key: SnapshotKey, document: SnapshotDocument) {
        documents[key] = Stored(document, InMemorySnapshotDocumentStore.digestOf(document))
        notifyChanged(key)
    }

    fun remove(key: SnapshotKey) {
        documents.remove(key)
        notifyChanged(key)
    }

    fun notifyChanged(key: SnapshotKey) {
        if (!dropNotifications) {
            listeners.forEach { it.onChanged(listOf(key)) }
        }
    }

    fun reset() {
        listeners.forEach { it.onReset() }
    }

    fun revision(key: SnapshotKey): Long? = documents[key]?.document?.revision

    val listenerCount: Int get() = listeners.size

    /**
     * A stale local copy (a CoCache L2) per key: [read] answers from it when present, while [readIfChanged] always
     * reads the store of record.
     */
    val localCopies: MutableMap<SnapshotKey, SnapshotDocument> = ConcurrentHashMap()

    /** The age this store declares its local copies may have. */
    @Volatile
    var declaredLocalCopyMaxAge: Duration = Duration.ZERO

    override val localCopyMaxAge: Duration get() = declaredLocalCopyMaxAge

    /** When set, only conditional reads fail with it. */
    @Volatile
    var conditionalFailure: Throwable? = null

    /** Every key read, conditional or not, in order. */
    val readLog: MutableList<SnapshotKey> = CopyOnWriteArrayList()

    override fun read(key: SnapshotKey): CompletableFuture<SnapshotRead> {
        reads.incrementAndGet()
        readLog.add(key)
        val local = localCopies[key]
        if (local != null) {
            return CompletableFuture.completedFuture(
                SnapshotRead.Present(local, InMemorySnapshotDocumentStore.digestOf(local))
            )
        }
        return answer(key) { it }
    }

    override fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?): CompletableFuture<SnapshotRead> {
        conditionalReads.incrementAndGet()
        readLog.add(key)
        conditionalFailure?.let { return CompletableFuture.failedFuture(it) }
        return answer(
            key
        ) { read -> if (read is SnapshotRead.Present && read.digest == known) SnapshotRead.Unchanged else read }
    }

    private fun answer(key: SnapshotKey, map: (SnapshotRead) -> SnapshotRead): CompletableFuture<SnapshotRead> {
        val answer: CompletableFuture<SnapshotRead> = when {
            failure != null -> CompletableFuture.failedFuture(failure)
            undecodable && documents.containsKey(key) ->
                CompletableFuture.failedFuture(
                    IllegalStateException("decode", SnapshotFormatException("unknown schema"))
                )
            else -> {
                val stored = documents[key]
                CompletableFuture.completedFuture(
                    map(
                        if (stored == null) {
                            SnapshotRead.Absent
                        } else {
                            SnapshotRead.Present(
                                stored.document,
                                stored.digest
                            )
                        }
                    )
                )
            }
        }
        if (!hold) {
            return answer
        }
        val future = CompletableFuture<SnapshotRead>()
        pending.add(PendingRead(key, sequence.incrementAndGet(), answer, future))
        return future
    }

    fun releaseAll() {
        while (pending.isNotEmpty()) {
            pending.removeAt(0).release()
        }
    }

    /** Runs when a subscriber closes its subscription: the first thing a closing source does. */
    @Volatile
    var onUnsubscribe: (() -> Unit)? = null

    override fun subscribe(listener: SnapshotChangeListener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable {
            listeners.remove(listener)
            onUnsubscribe?.invoke()
        }
    }
}

/** Throws from every callback, to prove that a failing meter changes nothing. */
internal class ThrowingMetrics : SnapshotMetrics {
    private fun fail(): Nothing = throw IllegalStateException("meter down")

    override fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) = fail()

    override fun onCompile(kind: SnapshotKind, duration: Duration, succeeded: Boolean) = fail()

    override fun onInstall(entry: SnapshotEntry<*>) = fail()

    override fun onRegression(key: SnapshotKey, installedRevision: Long, loadedRevision: Long) = fail()

    override fun onDocumentLost(key: SnapshotKey, lastRevision: Long) = fail()

    override fun onLagExceeded(key: SnapshotKey, lag: Duration, escalated: Boolean) = fail()
}

/** Records every [SnapshotMetrics] event. */
internal class RecordingMetrics : SnapshotMetrics {
    val loads: MutableList<Pair<SnapshotKind, LoadOutcome>> = CopyOnWriteArrayList()
    val installs: MutableList<SnapshotEntry<*>> = CopyOnWriteArrayList()
    val regressions: MutableList<Triple<SnapshotKey, Long, Long>> = CopyOnWriteArrayList()
    val lost: MutableList<Pair<SnapshotKey, Long>> = CopyOnWriteArrayList()
    val compiles: MutableList<Pair<SnapshotKind, Boolean>> = CopyOnWriteArrayList()
    val lagExceeded: MutableList<Triple<SnapshotKey, Duration, Boolean>> = CopyOnWriteArrayList()

    override fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) {
        loads.add(kind to outcome)
    }

    override fun onCompile(kind: SnapshotKind, duration: Duration, succeeded: Boolean) {
        compiles.add(kind to succeeded)
    }

    override fun onInstall(entry: SnapshotEntry<*>) {
        installs.add(entry)
    }

    override fun onRegression(key: SnapshotKey, installedRevision: Long, loadedRevision: Long) {
        regressions.add(Triple(key, installedRevision, loadedRevision))
    }

    override fun onDocumentLost(key: SnapshotKey, lastRevision: Long) {
        lost.add(key to lastRevision)
    }

    override fun onLagExceeded(key: SnapshotKey, lag: Duration, escalated: Boolean) {
        lagExceeded.add(Triple(key, lag, escalated))
    }
}

/** Runs tasks on the calling thread: compilation becomes synchronous, so single-threaded tests are deterministic. */
val directExecutor: Executor = Executor { it.run() }

/** A source with no periodic revalidation: tests call [SnapshotSource.revalidate] or [SnapshotSource.tick] instead. */
internal fun testSource(
    store: SnapshotDocumentStore,
    compiler: FakeCompiler = FakeCompiler(),
    options: SnapshotOptions = SnapshotOptions(revalidateInterval = Duration.ZERO),
    clock: Clock = ManualClock(),
    metrics: SnapshotMetrics = SnapshotMetrics.NOOP,
    compileExecutor: Executor? = directExecutor,
    ticker: AtomicLong = AtomicLong(),
    scheduler: ScheduledExecutorService? = null,
): SnapshotSource<Compiled, Compiled> =
    appSource(
        store,
        compiler,
        compiler,
        options,
        clock,
        metrics,
        compileExecutor,
        scheduler
    ) { ticker.incrementAndGet() }

/**
 * The source shape most tests use: the shared platform document and one family of app documents, so a test reads
 * "the shared document" and "an app" without the tenant family. The guarantees hold for every key of every family.
 */
internal fun <P : Any, A : Any> appSource(
    store: SnapshotDocumentStore,
    sharedCompiler: SnapshotCompiler<P>,
    appCompiler: SnapshotCompiler<A>,
    options: SnapshotOptions = SnapshotOptions(),
    clock: Clock = Clock.systemUTC(),
    metrics: SnapshotMetrics = SnapshotMetrics.NOOP,
    compileExecutor: Executor? = null,
    scheduler: ScheduledExecutorService? = null,
    ticker: LongSupplier = LongSupplier(System::nanoTime),
): SnapshotSource<P, A> = SnapshotSource(
    store,
    SnapshotKey.Platform,
    sharedCompiler,
    listOf(SnapshotFamily(SnapshotKind.APP, appCompiler, options.maxApps)),
    options,
    clock,
    metrics,
    compileExecutor,
    scheduler,
    ticker,
)

/** Whether [key] is installed. Unlike [SnapshotSource.peek], it does not count as an access, so waiting on it cannot change the LRU order. */
fun SnapshotSource<*, *>.holds(key: SnapshotKey): Boolean = entries().any { it.key == key }

val Snapshot<Compiled, Compiled>.policiesCompiled: Compiled
    get() = (policies as DocumentState.Present).compiled

val Snapshot<Compiled, Compiled>.appCompiled: Compiled
    get() = (app as DocumentState.Present).compiled

val APP_A = SnapshotKey.App("app-a")
val APP_B = SnapshotKey.App("app-b")
val TENANT_A = SnapshotKey.Tenant("tenant-a")
val TENANT_B = SnapshotKey.Tenant("tenant-b")
val APP_C = SnapshotKey.App("app-c")
