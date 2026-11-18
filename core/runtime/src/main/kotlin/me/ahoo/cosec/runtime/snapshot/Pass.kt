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
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** One periodic pass: [done] completes when it ends, or, once abandoned, when the pass that replaced it does. */
internal class Pass(
    val done: CompletableFuture<Void>,
    /** [KeyContext.nanoTime], read each time. */
    private val nanoTime: () -> Long,
    private val storeReadTimeout: Duration,
) {
    constructor(done: CompletableFuture<Void>, context: KeyContext) :
        this(done, { context.nanoTime.get().asLong }, context.options.storeReadTimeout)

    /**
     * When this pass started, in [KeyContext.nanoTime]: a load that started at or before it may have read before a
     * change this pass must install, so the pass never counts that load's read as its own (`SNP-6`).
     */
    val startedNanos: Long = nanoTime()

    /** When a document of this pass last finished, in [KeyContext.nanoTime]: the start, until one does. */
    @Volatile
    private var progressedAt: Long = startedNanos

    /** The reads this pass has in flight, with the lanes about to start one; [ABANDONED] once it is abandoned. */
    private val reads = AtomicInteger()

    val abandoned: Boolean get() = reads.get() == ABANDONED

    fun progressed() {
        progressedAt = nanoTime()
    }

    /** A read of this pass starting, or null once the pass is abandoned. */
    fun startRead(): PassRead? {
        while (true) {
            val count = reads.get()
            if (count == ABANDONED) {
                return null
            }
            if (reads.compareAndSet(count, count + 1)) {
                return PassRead(this)
            }
        }
    }

    fun readEnded() {
        reads.decrementAndGet()
    }

    /**
     * Abandons this pass if it has finished no document for [SnapshotOptions.storeReadTimeout] and has no read in
     * flight; only one with no read can be, so [startRead] never succeeds after this does.
     */
    fun abandonIfStalled(now: Long): Boolean =
        now - progressedAt > storeReadTimeout.toNanos() && reads.compareAndSet(0, ABANDONED)

    /** This abandoned pass ends when [successor] does. */
    fun follow(successor: Pass) {
        successor.done.whenComplete { _, error ->
            if (error != null) done.completeExceptionally(error) else done.complete(null)
        }
    }

    private companion object {
        /** A [Pass]'s count of reads once it is abandoned: no read can start any more. */
        const val ABANDONED = -1
    }
}
