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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.SlotModelStore.Content
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import java.time.Duration
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture

/** What the model's compiler produces: which document, and the inputs it was compiled with. */
internal class ModelCompiled(val key: SnapshotKey, val revision: Long, val inputs: Long)

/**
 * The reference model of the snapshot source (RFC 0013 B2b gate 1). It knows the store's versions, the reads the source
 * started, the entries it installed (as its metrics report them) and every future it handed out, and nothing of how the
 * slot does its work. [afterEvent] checks, after every scripted event:
 *
 * 1. **Installs never go back**: an install comes from the newest version a live read answered, never older than the
 *    one it replaces; a revision below the one installed last only after a failover, and then reported as a regression.
 * 2. **A failure installs nothing**: every install is what a live read answered (a document, its absence, an undecodable
 *    value), or a failed entry after [recompile]; what is served is always the last install. A family document is never
 *    installed compiled with inputs older than the current ones, and a revision that does not compile leaves no older
 *    one in service.
 * 3. **At most one live read per key**: a read is live until it is answered, the source gives it up at
 *    `storeReadTimeout`, or the source closes. An answer that comes after that never installs (2).
 * 4. **Every waiter is answered, and correctly**: a request by `coldLoadTimeout`, with entries that were installed and
 *    are not older than what was served at the call; `revalidate`, `reload` and `recompile` only after a read of each of
 *    their documents that started after the call has ended (`SNP-13`), `recompile` with nothing older than its inputs
 *    left in service; everything once the source is closed or the run settles.
 */
internal class SlotModel(
    private val store: SlotModelStore,
    private val threads: SlotModelThreads,
    private val options: SnapshotOptions,
    private val familyKeys: List<SnapshotKey>,
) {
    /** The scripted event now running; reads and calls are stamped with it. */
    var event = 0L

    /** The inputs the model's compiler compiles with: [recompile] changes them. */
    var inputs = 0L
        private set

    var closedAt = -1L
    val violations = mutableListOf<String>()
    val counts = Counts()

    private val versions = IdentityHashMap<SnapshotEntry<*>, Int>()
    private val lastInstall = HashMap<SnapshotKey, SnapshotEntry<*>>()
    private val lastVersion = HashMap<SnapshotKey, Int>()

    /** When the read behind each key's last install started; and when each recompile was called. */
    private val installedFrom = HashMap<SnapshotKey, Long>()
    private val recompiles = mutableListOf<Long>()

    /** The revision of the document last installed per key, whatever came between (an absence) and through eviction. */
    private val lastRevision = HashMap<SnapshotKey, Long>()
    private val evictedAt = HashMap<SnapshotKey, Long>()
    private val expectedRegressions = mutableListOf<Pair<SnapshotKey, Long>>()

    /** The documents whose compilation failed during the event now running. */
    private val failedCompiles = mutableListOf<Pair<SnapshotKey, Long>>()
    private val regressions = mutableListOf<Pair<SnapshotKey, Long>>()
    val obligations = mutableListOf<Obligation>()

    /** What the scripted run reached, for the vacuity report: how often each kind of event and check happened. */
    class Counts {
        var installs = 0
        var failures = 0
        var answered = 0
        var readTimeouts = 0
        var coldTimeouts = 0
        var evictions = 0
        var regressions = 0
    }

    /** A future the source handed out, and what must hold when it completes. */
    inner class Obligation(val kind: String, val keys: Set<SnapshotKey>, private val deadline: Long?) {
        lateinit var future: CompletableFuture<*>
        val at = event
        private val inputsAtCall = inputs
        private val servedAtCall = keys.associateWith { key -> lastInstall[key]?.let { versions[it] } ?: -1 }

        fun overdue(): Boolean = deadline != null && threads.nanos >= deadline && !future.isDone

        fun completed(value: Any?) {
            counts.answered++
            when (kind) {
                "request" -> answeredRequest(value)
                "revalidate", "reload" -> readAfter()
                "recompile" -> {
                    readAfter()
                    recompiled()
                }
            }
        }

        private fun answeredRequest(value: Any?) {
            val entries = when (value) {
                is SnapshotSet<*, *> -> listOf(value.shared) + value.scoped
                is Snapshot<*, *> -> listOfNotNull(value.policiesEntry, value.appEntry)
                else -> emptyList()
            }
            for (entry in entries) {
                if (entry.state !is DocumentState.Present) continue
                val version = versions[entry]
                if (version == null) {
                    violations += "$this answered $entry, which was never installed"
                } else if (version < (servedAtCall[entry.key] ?: -1)) {
                    violations += "$this answered version $version of ${entry.key}, older than the one served at the call"
                }
            }
        }

        /** Each document has had a read that started after the call and ended, or left the source, or the source closed. */
        private fun readAfter() {
            if (closedAt >= 0) return
            for (key in keys) {
                val read = store.reads.any { it.key == key && it.startedAt >= at && ended(it) }
                if (!read && (evictedAt[key] ?: -1) < at) {
                    violations += "$this completed with no read of $key that started after the call and ended"
                }
            }
        }

        private fun recompiled() {
            if (closedAt >= 0) return
            for (key in keys) {
                val compiled = (lastInstall[key]?.state as? DocumentState.Present)?.compiled as? ModelCompiled ?: continue
                if (compiled.inputs < inputsAtCall) {
                    violations += "$this completed with $key compiled with inputs ${compiled.inputs} still in service"
                }
            }
        }

        override fun toString(): String = "$kind of $keys called at event $at"
    }

    /** [call] hands out a future: what must hold when it completes is fixed before the call, as of now. */
    fun handedOut(kind: String, keys: Set<SnapshotKey>, deadline: Duration? = null, call: () -> CompletableFuture<*>) {
        val obligation = Obligation(kind, keys, deadline?.let { threads.nanos + it.toNanos() })
        obligation.future = call()
        obligations += obligation
        obligation.future.whenComplete { value, _ -> obligation.completed(value) }
    }

    /** The keys a `revalidate` called now must read again: every one installed. */
    fun installedKeys(): Set<SnapshotKey> = lastInstall.keys.toSet()

    /** The keys a `recompile` called now must load again: family documents compiled, or being read. */
    fun recompiledKeys(): Set<SnapshotKey> =
        familyKeys.filter { key ->
            lastInstall[key]?.origin?.compiled == true || store.pending.any { it.key == key && !ended(it) }
        }.toSet()

    /**
     * Whether the source is done with [read]: it answered, or the source gave it up, which it does once
     * `storeReadTimeout` has passed since the read started (the timer that ends it falls due then) or once it closes.
     */
    fun ended(read: SlotModelStore.Read): Boolean =
        read.answered || read.abandoned || closedAt >= 0 ||
            threads.nanos >= read.startedNanos + options.storeReadTimeout.toNanos()

    fun recompile() {
        inputs++
        recompiles += event
    }

    val metrics = object : SnapshotMetrics {
        override fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) {
            if (outcome == LoadOutcome.TIMEOUT) counts.coldTimeouts++
        }

        override fun onInstall(entry: SnapshotEntry<*>) = installed(entry)

        override fun onRegression(key: SnapshotKey, installedRevision: Long, loadedRevision: Long) {
            regressions += key to loadedRevision
        }
    }

    private fun installed(entry: SnapshotEntry<*>) {
        counts.installs++
        val key = entry.key
        val (version, readStart) = justify(entry) ?: return
        val previous = lastVersion[key] ?: -1
        if (version < previous) {
            violations += "installed version $version of $key after version $previous"
        }
        val content = store.contentAt(key, version)
        if (content is Content.Document && entry.origin != EntryOrigin.RECOMPILE_PENDING) {
            val last = lastRevision[key] ?: 0
            if (content.document.revision < last) expectedRegressions += key to content.document.revision
            lastRevision[key] = content.document.revision
        }
        val compiled = (entry.state as? DocumentState.Present)?.compiled as? ModelCompiled
        if (key in familyKeys && compiled != null && compiled.inputs < inputs) {
            violations += "installed $key compiled with inputs ${compiled.inputs} after they changed to $inputs"
        }
        versions[entry] = version
        lastVersion[key] = version
        lastInstall[key] = entry
        installedFrom[key] = readStart
    }

    /**
     * The store version [entry] comes from, the newest one a live read answered with what it holds, and when that read
     * started; null if none.
     */
    private fun justify(entry: SnapshotEntry<*>): Pair<Int, Long>? {
        val key = entry.key
        val answers = store.reads.filter { it.key == key && it.answered && !it.abandoned && !it.failed }
        fun newest(matches: (Content) -> Boolean): Pair<Int, Long>? =
            answers.filter { matches(store.contentAt(key, it.answeredVersion)) }
                .maxWithOrNull(compareBy({ it.answeredVersion }, { it.startedAt }))
                ?.let { it.answeredVersion to it.startedAt }
        val version = when (entry.origin) {
            EntryOrigin.DOCUMENT, EntryOrigin.UNCOMPILABLE ->
                newest { it is Content.Document && it.digest == entry.digest }
            EntryOrigin.ABSENT, EntryOrigin.LOST -> newest { it == Content.Absent }
            EntryOrigin.UNDECODABLE -> newest { it == Content.Undecodable }
            EntryOrigin.RECOMPILE_PENDING -> outdated(entry)
            EntryOrigin.TRANSIENT -> null
        }
        if (version == null) violations += "installed $entry (${entry.origin}), which no live read answered"
        return version
    }

    /**
     * A failed entry in place of the last install of its key, when inputs changed after the read it came from started:
     * its compilation may have used the old ones. Its version is that install's.
     */
    private fun outdated(entry: SnapshotEntry<*>): Pair<Int, Long>? {
        val from = installedFrom[entry.key] ?: return null
        val changed = recompiles.any { it >= from }
        return if (changed && lastInstall[entry.key]?.digest == entry.digest) lastVersion.getValue(entry.key) to from else null
    }

    /** The checks after each event; [served] is what the source holds now ([SnapshotSource.entries]). */
    fun afterEvent(served: List<SnapshotEntry<*>>, closed: Boolean) {
        if (closed && closedAt < 0) closedAt = event
        abandonReads(closed)
        checkServed(served, closed)
        for (key in store.reads.map { it.key }.distinct()) {
            val live = store.pending.count { it.key == key && !it.abandoned }
            if (live > 1) violations += "$live live reads of $key at once"
        }
        obligations.filter { it.overdue() && closedAt < 0 }.forEach {
            violations += "$it not answered within coldLoadTimeout"
        }
        checkRegressions()
        checkFailedCompiles()
    }

    private fun checkRegressions() {
        for (expected in expectedRegressions) {
            if (expected !in regressions) violations += "installed revision ${expected.second} of ${expected.first} below one installed before, unreported"
        }
        counts.regressions += expectedRegressions.size
        expectedRegressions.clear()
        regressions.clear()
    }

    /** A revision that does not compile is installed failed: the one before it is not served in its place. */
    private fun checkFailedCompiles() {
        for ((key, revision) in failedCompiles) {
            val served = lastInstall[key]?.takeIf { it.state is DocumentState.Present } ?: continue
            if (closedAt < 0 && served.revision < revision) {
                violations += "revision $revision of $key failed to compile, and revision ${served.revision} is still served"
            }
        }
        failedCompiles.clear()
    }

    private fun abandonReads(closed: Boolean) {
        for (read in store.pending) {
            if (read.abandoned) continue
            if (closed || threads.nanos >= read.startedNanos + options.storeReadTimeout.toNanos()) {
                read.abandoned = true
                if (!closed) counts.readTimeouts++
            }
        }
    }

    private fun checkServed(served: List<SnapshotEntry<*>>, closed: Boolean) {
        val byKey = served.associateBy { it.key }
        for (entry in served) {
            if (entry !== lastInstall[entry.key]) violations += "serves $entry, which is not the last install of ${entry.key}"
        }
        for (key in lastInstall.keys.toList()) {
            if (key !in byKey && !closed) {
                lastInstall.remove(key)
                evictedAt[key] = event
                counts.evictions++
            }
        }
    }

    /** Every future handed out is answered: after close, and once the run has settled. */
    fun allAnswered(moment: String) {
        // A prewarm waits for its documents to load without failing; one that serves a failure keeps it waiting.
        fun mayWait(it: Obligation) = it.kind == "prewarm" && closedAt < 0 &&
            it.keys.any { key -> lastInstall[key]?.state !is DocumentState.Present && lastInstall[key]?.state != DocumentState.Absent }
        obligations.filterNot { it.future.isDone || mayWait(it) }.forEach { violations += "$it still waits $moment" }
    }

    /** After the run settled and every document was read again: each one serves what the store holds now. */
    fun converged(key: SnapshotKey, entry: SnapshotEntry<*>) {
        val state = entry.state
        val wanted = store.current(key)
        val ok = when (wanted) {
            is Content.Document ->
                state is DocumentState.Present && entry.digest == wanted.digest &&
                    (key !in familyKeys || (state.compiled as ModelCompiled).inputs == inputs)
            Content.Absent -> entry.origin.storedAbsent
            Content.Undecodable -> entry.origin == EntryOrigin.UNDECODABLE
        }
        if (!ok) violations += "settled on $entry (${entry.origin}) for $key while the store holds $wanted"
    }

    /** The compiler of the run: records its inputs, and fails, overflows or changes its inputs when told to. */
    inner class Compiler(private val onRecompile: () -> Unit) : SnapshotCompiler<ModelCompiled> {
        var failNext = false
        var overflowNext = false
        var recompileDuringNext = false

        override fun compile(key: SnapshotKey, document: SnapshotDocument): ModelCompiled {
            val used = inputs
            if (recompileDuringNext) {
                recompileDuringNext = false
                onRecompile()
            }
            if (overflowNext || failNext) {
                counts.failures++
                failedCompiles += key to document.revision
            }
            if (overflowNext) {
                overflowNext = false
                throw StackOverflowError("scripted")
            }
            if (failNext) {
                failNext = false
                throw IllegalStateException("scripted compile failure")
            }
            return ModelCompiled(key, document.revision, used)
        }
    }
}
