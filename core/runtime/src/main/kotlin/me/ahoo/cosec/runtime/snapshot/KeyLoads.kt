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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.KeyEffect.Level
import me.ahoo.cosec.runtime.snapshot.SnapshotFamilies.Seen
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * A load's life inside [KeyTransitions] (RFC 0013 B2b): how it starts, what its read and its compilation find, and how
 * it ends. Each function names the [Slot] code it replaces, and decides as that code does.
 */
internal class KeyLoads<T : Any>(private val config: KeyConfig) {
    private val key = config.key

    /** `newLoad`: a load starting now, put in the state; its read starts with [startRead]. */
    fun newLoad(step: KeyStepBuilder<T>, force: Boolean, pass: Pass?, handle: KeyHandle<T>?): LoadState<T> {
        val id = step.serial()
        val state = step.state
        val known = state.installed
        // Only a compiled entry depends on the compiler's inputs; the shared document is not recompiled.
        val compiled = known != null && known.origin.compiled
        val recompiles = !config.shared && compiled && state.installedEpoch < step.moment.epoch
        // readFor: see the class comment of SnapshotSource for why seen keys never use a local copy.
        val local = known == null && step.seen == null
        val digest = when {
            local || known == null || force || recompiles -> null
            known.origin.compilesAgain -> null
            else -> known.digest
        }
        val load = LoadState(
            id = id,
            known = known,
            force = force,
            epoch = step.moment.epoch,
            recompiles = recompiles,
            pass = pass?.let(::PassTicket),
            startedAt = step.moment.instant,
            startedNanos = step.moment.nanos,
            fromLocalCopy = local,
            askedDigest = digest,
            phase = LoadPhase.Reading,
            handle = handle ?: step.handle(),
        )
        step.state = step.state.copy(load = load)
        return load
    }

    /** `read`: counts the pass's read, then starts the store's. */
    fun startRead(step: KeyStepBuilder<T>, load: LoadState<T>) {
        load.pass?.let { step.emit(KeyEffect.StartPassRead(it)) }
        step.emit(KeyEffect.StartRead(load.id, load.fromLocalCopy, load.askedDigest))
    }

    /** `onRead` for an answer: the read is over, and the pass's read with it. */
    fun onRead(step: KeyStepBuilder<T>, load: LoadState<T>, read: SnapshotRead) {
        val reading = endRead(step, load)
        when (read) {
            is SnapshotRead.Present -> {
                val seen = Seen(read.document.revision, read.digest)
                step.emit(KeyEffect.RecordSeen(seen, ifAbsent = true))
                step.seen = step.seen ?: seen
                step.state = step.state.copy(load = reading.copy(phase = LoadPhase.Compiling(read)))
                step.emit(KeyEffect.Compile(load.id, read))
            }

            SnapshotRead.Absent -> onAbsent(step, reading)
            SnapshotRead.Unchanged -> onUnchanged(step, reading)
        }
    }

    /** `onRead` for an error, a timeout included: undecodable when it is a format failure, otherwise transient. */
    fun onReadFailed(step: KeyStepBuilder<T>, load: LoadState<T>, error: Throwable) {
        val reading = endRead(step, load)
        val cause = unwrap(error)
        if (cause.isFormatFailure()) onUndecodable(step, reading, cause) else onTransientFailure(step, reading, cause)
    }

    /** `compileAndInstall`, once the compile pool compiled the document. */
    fun onCompiled(
        step: KeyStepBuilder<T>,
        load: LoadState<T>,
        read: SnapshotRead.Present,
        compiled: KeyEvent.Compiled<T>
    ) {
        step.state = step.state.copy(retryDelay = config.rejectedReloadRetry)
        val present = compiled.state is DocumentState.Present
        step.emit(KeyEffect.Report(KeyEffect.Metric.Compiled(compiled.nanos, present)))
        val origin = if (present) EntryOrigin.DOCUMENT else EntryOrigin.UNCOMPILABLE
        val confirmedAt = load.confirmedAt(config.localCopyMaxAge)
        finish(
            step,
            load,
            SnapshotEntry(key, compiled.state, read.document.revision, read.digest, confirmedAt, origin),
            install = true
        )
    }

    /**
     * RC1-43: a document that compiles on the control plane can overflow a smaller data-plane stack. Ending the load as
     * a transient failure would keep the previous revision, and a new DENY would never apply.
     */
    fun onCompileCrashed(
        step: KeyStepBuilder<T>,
        load: LoadState<T>,
        read: SnapshotRead.Present,
        failure: SnapshotUnavailableException
    ) {
        val confirmedAt = load.confirmedAt(config.localCopyMaxAge)
        val entry =
            SnapshotEntry(
                key,
                DocumentState.Failed(failure),
                read.document.revision,
                read.digest,
                confirmedAt,
                EntryOrigin.UNCOMPILABLE
            )
        finish(step, load, entry, install = true)
    }

    /**
     * `onTransientFailure`: keep what is installed (F6), or answer failed without installing (F1). An entry compiled
     * with inputs that have changed since is not kept: it fails closed until a retry compiles it again. A reload
     * [rejected] by the compile queue retries, and so does one that keeps an entry that failed to compile.
     */
    fun onTransientFailure(step: KeyStepBuilder<T>, load: LoadState<T>, cause: Throwable, rejected: Boolean = false) {
        val known = load.known
        if (known != null && load.recompiles) {
            step.emit(
                KeyEffect.Log(
                    Level.WARN,
                    "Compiling snapshot document [$key] again failed; failing closed until it compiles.",
                    cause
                )
            )
            val error =
                SnapshotUnavailableException(key, "compiled with inputs that changed, and not compiled again", cause)
            val failed =
                SnapshotEntry(
                    key,
                    DocumentState.Failed(error),
                    known.revision,
                    known.digest,
                    known.confirmedAt,
                    EntryOrigin.RECOMPILE_PENDING
                )
            finish(step, load, failed, install = true)
            retryLater(step)
            return
        }
        if (known != null) {
            step.emit(
                KeyEffect.Log(
                    Level.WARN,
                    "Reloading snapshot document [$key] failed; keeping revision ${known.revision}.",
                    cause
                )
            )
            finish(step, load, known, install = false)
            if (rejected || known.origin.compilesAgain) {
                retryLater(step)
            }
            return
        }
        step.emit(KeyEffect.Log(Level.WARN, "Loading snapshot document [$key] failed.", cause))
        finish(step, load, transientFailure(key, load.startedAt, cause), install = false)
    }

    /**
     * `finish` and `advance`: ends [load] with [entry]. A family entry compiled in an earlier epoch may have compiled
     * with inputs that a recompile replaced: another load follows at once, and a compiled document is not installed.
     * Its waiters get what that load ends with, never the old compilation. The effects come in [Slot]'s order:
     * the install, eviction, the next read or the pending reload's answer, then the load's waiters.
     */
    fun finish(step: KeyStepBuilder<T>, load: LoadState<T>, entry: SnapshotEntry<T>, install: Boolean) {
        // Whatever ended the load (a read that failed to start included), its read is over.
        load.pass?.let { step.emit(KeyEffect.EndPassRead(it)) }
        val stale = install && config.compiledBefore(load, entry, step.moment)
        val withheld = stale && entry.origin == EntryOrigin.DOCUMENT
        val installs = install && !withheld
        if (installs) {
            recordInstall(step, entry)
        }
        val pending = step.state.next
        val nextLoad = advance(step, load, entry.takeIf { installs }, stale)
        val answer = if (withheld && nextLoad == null) outdated(key, step.moment) else entry
        housekeep(step, installs)
        if (nextLoad != null) startRead(step, nextLoad) else pending?.let { step.emit(KeyEffect.Answer(it, answer)) }
        answerWaiters(step, load, answer, nextLoad.takeIf { withheld })
    }

    /**
     * `advance`: [installed] is written, the load is over, and, if another load is wanted (a change came, the entry is
     * [stale], or a local copy answered) and the key is open, the next load starts in the same step, with the pending
     * reload's handle, so no other load and no removal can come between them.
     */
    private fun advance(
        step: KeyStepBuilder<T>,
        load: LoadState<T>,
        installed: SnapshotEntry<T>?,
        stale: Boolean
    ): LoadState<T>? {
        val before = step.state
        val wanted = before.again || stale || (installed != null && load.fromLocalCopy)
        step.state = before.copy(
            installed = installed ?: before.installed,
            installedEpoch = if (installed != null) load.epoch else before.installedEpoch,
            load = null,
            again = false,
            againForced = false,
            next = null,
        )
        val open = !step.moment.closed && !before.removed
        return if (wanted && open) newLoad(step, before.againForced, null, before.next) else null
    }

    /** `answerWaiters`: [load]'s handles get [answer], or, for a withheld compilation, what [recompiling] ends with. */
    private fun answerWaiters(
        step: KeyStepBuilder<T>,
        load: LoadState<T>,
        answer: SnapshotEntry<T>,
        recompiling: LoadState<T>?
    ) {
        val waiting = listOf(load.handle) + load.alsoAnswers
        if (recompiling == null) {
            waiting.forEach { step.emit(KeyEffect.Answer(it, answer)) }
            return
        }
        step.state = step.state.copy(load = recompiling.copy(alsoAnswers = recompiling.alsoAnswers + waiting))
    }

    private fun endRead(step: KeyStepBuilder<T>, load: LoadState<T>): LoadState<T> {
        step.emit(KeyEffect.CancelReadTimeout(load.id))
        load.pass?.let { step.emit(KeyEffect.EndPassRead(it)) }
        return load.copy(pass = null).also { step.state = step.state.copy(load = it) }
    }

    private fun onUnchanged(step: KeyStepBuilder<T>, load: LoadState<T>) {
        val known = load.known
        when {
            load.fromLocalCopy -> onTransientFailure(
                step,
                load,
                IllegalStateException("Unchanged answer to an unconditional read.")
            )
            load.askedDigest == null -> onAbsent(step, load)
            known == null -> onTransientFailure(
                step,
                load,
                IllegalStateException("Unchanged answer without a known digest.")
            )
            else -> keep(step, load, known)
        }
    }

    private fun onUndecodable(step: KeyStepBuilder<T>, load: LoadState<T>, cause: Throwable) {
        val seen = Seen(0, null)
        step.emit(KeyEffect.RecordSeen(seen, ifAbsent = true))
        step.seen = step.seen ?: seen
        val known = load.known
        if (known != null && known.origin == EntryOrigin.UNDECODABLE) {
            keep(step, load, known)
            return
        }
        val failure = SnapshotUnavailableException(key, "the stored document cannot be decoded", cause)
        finish(step, load, config.entryOf(load, DocumentState.Failed(failure), EntryOrigin.UNDECODABLE), install = true)
    }

    private fun onAbsent(step: KeyStepBuilder<T>, load: LoadState<T>) {
        val known = load.known
        if (known != null && known.origin.storedAbsent) {
            keep(step, load, known)
            return
        }
        val lastSeen = step.seen
        if (lastSeen == null) {
            finish(step, load, config.entryOf(load, DocumentState.Absent, EntryOrigin.ABSENT), install = true)
            return
        }
        val error = SnapshotUnavailableException(
            key,
            "the store has no document, but revision ${lastSeen.revision} was seen; it may have lost data",
        )
        finish(step, load, config.entryOf(load, DocumentState.Failed(error), EntryOrigin.LOST), install = true)
        // After the install: a throwing meter must not keep the lost document in service.
        step.emit(
            KeyEffect.Log(Level.ERROR, "Snapshot document [$key] disappeared from the store; failing closed.", error)
        )
        step.emit(KeyEffect.Report(KeyEffect.Metric.DocumentLost(lastSeen.revision)))
    }

    /** What the store answered still holds: confirmed as of this load, and kept. */
    private fun keep(step: KeyStepBuilder<T>, load: LoadState<T>, known: SnapshotEntry<T>) {
        step.emit(KeyEffect.Confirm(known, load.confirmedAt(config.localCopyMaxAge)))
        finish(step, load, known, install = false)
    }

    /** `retryLater`: with a delay that doubles while the compile queue stays full, up to the revalidation interval. */
    private fun retryLater(step: KeyStepBuilder<T>) {
        val delay = step.state.retryDelay
        step.state = step.state.copy(retryDelay = minOf(delay.multipliedBy(2), config.revalidateInterval))
        step.emit(KeyEffect.ScheduleRetry(delay))
    }

    /** `recordInstall`: the revision seen, and a regression from the one seen before. */
    private fun recordInstall(step: KeyStepBuilder<T>, entry: SnapshotEntry<T>) {
        var regressedFrom: Seen? = null
        if (entry.revision != 0L) {
            val previous = step.seen
            val seen = Seen(entry.revision, entry.digest)
            step.seen = seen
            step.emit(KeyEffect.RecordSeen(seen, ifAbsent = false))
            val regressed = previous != null &&
                (entry.revision < previous.revision || (entry.revision == previous.revision && entry.digest != previous.digest))
            regressedFrom = previous.takeIf { regressed }
        }
        regressedFrom?.let {
            step.emit(
                KeyEffect.Log(
                    Level.ERROR,
                    "Snapshot document [$key] regressed from revision ${it.revision} to ${entry.revision}; " +
                        "the store may have lost a write (for example a Redis failover). Installing what the store holds.",
                )
            )
        }
        step.emit(KeyEffect.Install(entry, step.state.installed, regressedFrom))
    }

    /** `housekeep`: an empty key is dropped, and an install evicts beyond the family's bound; the shared key has no family. */
    private fun housekeep(step: KeyStepBuilder<T>, installed: Boolean) {
        when {
            config.shared -> Unit
            step.state.installed == null -> step.emit(KeyEffect.DropIfEmpty)
            installed -> step.emit(KeyEffect.EvictBeyond)
        }
    }
}

private fun unwrap(error: Throwable): Throwable {
    var current = error
    while (current is CompletionException || current is ExecutionException) {
        current = current.cause ?: return current
    }
    return current
}

private fun Throwable.isFormatFailure(): Boolean =
    generateSequence(this) { it.cause }.take(FORMAT_CAUSE_DEPTH).any { it is SnapshotFormatException }

private const val FORMAT_CAUSE_DEPTH = 16
