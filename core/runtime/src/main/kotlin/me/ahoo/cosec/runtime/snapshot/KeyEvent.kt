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
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException

/**
 * What happens to one key (RFC 0013 B2b): the input of [KeyTransitions.next], one per [KeyRunner] call or completion it
 * stands for. An event about a load carries the load's [loadId]; one about a load that has ended, or about a read the
 * load has moved past, is dropped. [carried] is what the event brought in that must be answered whatever happens: a
 * waiter or a reply.
 *
 * An event the source's own work produces, a load's or its retry's, is a [LaneEvent]: the drain takes it from its load
 * lane, never counted against a caller's budget (RFC 0013 B2b, finding 2). Every other event is a caller's.
 */
internal sealed interface KeyEvent<out T : Any> {
    val loadId: Long? get() = null
    val carried: Any? get() = null

    /**
     * An event of the load lane: produced only by a load the runner itself started (its read's answer, its timeout, its
     * compilation, an effect that failed) or by a retry of one, and offered once. Each offer makes a fresh instance, so
     * [next] is the instance's own link in the lane: a plain field, written before the CAS that publishes it and read
     * after the swap that takes it ([KeyDrain]). A linked instance offered again fails an assertion.
     */
    sealed class LaneEvent<out T : Any> : KeyEvent<T> {
        @JvmField
        internal var next: Any? = null
    }

    /** `forRequest`, `usable`, `load`, `reload`; [waiter] is null when nobody waits (a change, a retry). */
    data class Requested(val ask: Ask, val waiter: Waiter?) : KeyEvent<Nothing> {
        override val carried: Any? get() = waiter
    }

    /** `onChanged`: reload if the key is installed or loading. */
    data object Changed : KeyEvent<Nothing>

    /** `markRecompile`, after the epoch moved: [reply] says whether the key is to be loaded again. */
    data class Recompile(val reply: CompletableFuture<Boolean>) : KeyEvent<Nothing> {
        override val carried: Any? get() = reply
    }

    /** The store answered load [loadId]'s read. */
    data class ReadDone(override val loadId: Long, val read: SnapshotRead) : LaneEvent<Nothing>()

    /** Load [loadId]'s read failed, or its start threw: undecodable when [cause] is a format failure. */
    data class ReadFailed(override val loadId: Long, val cause: Throwable) : LaneEvent<Nothing>()

    /** The store did not answer load [loadId]'s read within the store read timeout. */
    data class ReadTimedOut(override val loadId: Long) : LaneEvent<Nothing>()

    /** The compile pool compiled load [loadId]'s document into [state]. */
    data class Compiled<out T : Any>(override val loadId: Long, val state: DocumentState<T>) : LaneEvent<T>()

    /** Compiling load [loadId]'s document threw a `VirtualMachineError`, described by [failure] (RC1-43). */
    data class CompileCrashed(
        override val loadId: Long,
        val failure: SnapshotUnavailableException
    ) : LaneEvent<Nothing>()

    /** The compile pool refused load [loadId]'s compilation. */
    data class CompileRejected(override val loadId: Long, val cause: RejectedExecutionException) : LaneEvent<Nothing>()

    /** A reload the compile queue rejected tries again: an instance per retry, since a lane event is offered once. */
    class RetryDue : LaneEvent<Nothing>() {
        override fun toString() = "RetryDue"
    }

    /**
     * `removeIfIdle`: an eviction, or dropping an empty slot ([onlyIfEmpty]). [claim] is what the runner found before
     * feeding it, under the drain flag: whether it claimed the family's decrement for this removal (`SNP-14`).
     */
    data class Evict(
        val onlyIfEmpty: Boolean,
        val claim: Claim,
        val reply: CompletableFuture<Boolean>
    ) : KeyEvent<Nothing> {
        override val carried: Any? get() = reply
    }

    /** `endLoad`: the source is closing, and no load starts after this. */
    data object Close : KeyEvent<Nothing>

    /** An effect of load [loadId] threw [cause]: the load ends as a transient failure, if it has not ended. */
    data class EffectFailed(override val loadId: Long, val cause: Throwable) : LaneEvent<Nothing>()

    /** What a [Requested] asks for. */
    sealed interface Ask {
        /** A request: the installed entry, or the load in flight, bounded by the request's own timeout. */
        data object Request : Ask

        /** The installed entry if it has not failed, otherwise the load in flight. */
        data object Usable : Ask

        /**
         * The load in flight, or a new one. A periodic [pass] joins only a load that started after it, waits for the
         * load after one that started before it, and does not wait for one older than the store read timeout (`SNP-6`).
         */
        data class Load(val force: Boolean, val pass: Pass? = null) : Ask

        /** A load that starts after this event (`SNP-13`); a removed key's goes to the key's current runner. */
        data class Reload(val force: Boolean) : Ask

        /** As [Reload] for a key still held; a removed key is skipped (revalidation, reset, recompile's reloads). */
        data class ReloadIfHeld(val force: Boolean) : Ask
    }

    /** What the runner found for an [Evict]. */
    enum class Claim {
        /** No kind named: dropping an empty slot, or a test's eviction. The removal moves its count itself. */
        UNCLAIMED,

        /** Evictable, and the family's decrement is claimed: the removal is this claim. */
        CLAIMED,

        /** Not idle, already removed, or of the other kind by now. */
        NOT_EVICTABLE,

        /** Evictable, but the kind is at its bound: the pass stops. */
        AT_BOUND,
    }

    /** One who waits on a key: the transition says what it gets, the runner answers it. */
    interface Waiter
}
