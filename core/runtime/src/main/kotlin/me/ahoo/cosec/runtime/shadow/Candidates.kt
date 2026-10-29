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

package me.ahoo.cosec.runtime.shadow

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * A staged change's candidate for one document (RFC 0007 §6.4), as the control plane writes it to `candidate:<docKey>`:
 * the shadow window it belongs to, the revision of the head it was made on, and the document it would publish.
 */
class StoredCandidate(val window: String, val baseRevision: Long, val document: SnapshotDocument) {
    init {
        require(
            window.isNotEmpty() && baseRevision >= 0
        ) { "A candidate names its window and the revision of its base." }
    }
}

/** Where candidates are read from: the `candidate:` namespace. Reads never block the caller. */
@CoSecSpi
fun interface CandidateStore {
    suspend fun candidate(key: SnapshotKey): StoredCandidate?
}

/** A candidate as an instance holds it: compiled, or why it could not be. */
sealed interface Candidate {
    val window: String

    class Ready(override val window: String, val baseRevision: Long, val document: CompiledDocument) : Candidate

    /** The candidate does not compile on this instance: `CANDIDATE_ERROR`. */
    class Failed(override val window: String, val error: Throwable) : Candidate
}

/** The candidates the shadow listener compares against (RFC 0007 §6.4). Asked off the request's thread only. */
fun interface CandidateSource {
    /** The candidate of [key], or null when none is staged or it cannot be read now. */
    suspend fun candidate(key: SnapshotKey): Candidate?
}

/**
 * The data plane's [CandidateSource] (RFC 0007 §6.4): a second, small source over [store], apart from the enforced
 * documents: at most [maxEntries] documents (least recently used first out), each read again once it is [refreshAfter]
 * old, compiled with [compile], the instance's own compiler. It takes no part in readiness, and a read that fails only
 * counts ([failures]) and answers no candidate: the shadow window misses samples, the enforced decision never notices.
 */
class CachingCandidateSource(
    private val store: CandidateStore,
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

    /** Reads of the store that failed, since this source was made. */
    val failures: Long get() = failed.get()

    init {
        require(maxEntries > 0 && !refreshAfter.isNegative) { "Invalid candidate source options." }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun candidate(key: SnapshotKey): Candidate? {
        val now = clock.instant()
        synchronized(
            entries
        ) { entries[key] }?.takeIf { now.isBefore(it.at.plus(refreshAfter)) }?.let { return it.candidate }
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
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
