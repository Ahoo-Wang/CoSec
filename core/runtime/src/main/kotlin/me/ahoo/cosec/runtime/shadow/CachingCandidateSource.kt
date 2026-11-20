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

package me.ahoo.cosec.runtime.shadow

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.spi.shadow.CandidateStore
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * The data plane's [CandidateSource] (RFC 0007 §6.4): a second, small source over [store], apart from the enforced
 * documents: at most [maxEntries] documents (least recently used first out), each read again once it is [refreshAfter]
 * old, compiled with [compile], the instance's own compiler. It takes no part in readiness, and a read that fails only
 * counts ([failures]) and answers no candidate: the shadow window misses samples, the enforced decision never notices.
 * Requests that miss on one key at once share one read of the store. With a staged list ([staged], `CTL-47`) a document
 * it does not list answers no candidate without a read.
 */
@InternalCoSecApi
class CachingCandidateSource(
    /** The store of record it reads: what a [ShadowReporter] asks whether a window's candidate is still staged. */
    val store: CandidateStore,
    private val compile: (SnapshotKey, SnapshotDocument) -> CompiledDocument,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val refreshAfter: Duration = Duration.ofSeconds(10),
    private val clock: Clock = Clock.systemUTC(),
) : CandidateSource {
    private class Entry(val candidate: Candidate?, val at: Instant)

    private val entries = object : LinkedHashMap<SnapshotKey, Entry>(maxEntries, LOAD_FACTOR, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<SnapshotKey, Entry>?
        ): Boolean = size > maxEntries
    }

    private val failed = AtomicLong()

    /** The staged list as last read from [store], refreshed by the shadow reporter: what may be read and sampled. */
    val staged: StagedDocuments = StagedDocuments(store, clock)

    /** The read of each key in flight, which a request that misses on that key waits for instead of reading again. */
    private val reading = ConcurrentHashMap<SnapshotKey, CompletableDeferred<Candidate?>>()

    /** Reads of the store that failed, since this source was made. */
    val failures: Long get() = failed.get()

    init {
        require(maxEntries > 0 && !refreshAfter.isNegative) { "Invalid candidate source options." }
    }

    override suspend fun candidate(key: SnapshotKey): Candidate? {
        if (!staged.mayStage(key)) return null
        val now = clock.instant()
        synchronized(
            entries
        ) { entries[key] }?.takeIf { now.isBefore(it.at.plus(refreshAfter)) }?.let { return it.candidate }
        val mine = CompletableDeferred<Candidate?>()
        reading.putIfAbsent(key, mine)?.let { return it.await() }
        try {
            return read(key, now).also { mine.complete(it) }
        } finally {
            // A read cut short by its caller's cancellation answers the others no candidate, as a failed read does.
            mine.complete(null)
            reading.remove(key, mine)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun read(key: SnapshotKey, now: Instant): Candidate? {
        val stored = try {
            store.candidate(key)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            failed.incrementAndGet()
            return null
        } catch (e: Exception) {
            failed.incrementAndGet()
            log.debug(e) { "Reading the candidate of [$key] failed." }
            return null
        }
        val candidate = stored?.let { compiled(key, it) }
        synchronized(entries) { entries[key] = Entry(candidate, now) }
        return candidate
    }

    @Suppress("TooGenericExceptionCaught")
    private fun compiled(key: SnapshotKey, stored: StoredCandidate): Candidate = try {
        Candidate.Ready(stored.window, stored.baseRevision, compile(key, stored.document))
    } catch (e: Exception) {
        Candidate.Failed(stored.window, e)
    } catch (e: StackOverflowError) {
        // RC1-43: a document that compiles on the control plane can overflow a smaller stack here: it cannot compile.
        Candidate.Failed(stored.window, e)
    }

    @InternalCoSecApi
    companion object {
        /**
         * Enough to keep the apps a request reads besides its tenant: at 16, tenant keys pushed them out and a sampled
         * request read about 1.5 keys from Redis (measured over 10,000 tenants, beta.5); at 1,024, about 0.9.
         */
        const val DEFAULT_MAX_ENTRIES: Int = 1024
        private const val LOAD_FACTOR = 0.75f
    }
}
