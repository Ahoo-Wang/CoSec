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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

// The source's logger: moving the code did not move the name operators configure.
private val log = KotlinLogging.logger(SnapshotSource::class.java.name)

/**
 * The prewarming of a [SnapshotSource] (RFC 0013 B2a): loads keys until every one is installed and not failed, with
 * backoff, and remembers that one did. [usableOf] is the source's, first called by [start], after construction.
 */
internal class Prewarms(
    private val context: SlotContext,
    private val usableOf: (SnapshotKey) -> CompletableFuture<out SnapshotEntry<*>>,
) {
    private val pending: MutableSet<CompletableFuture<Void>> = ConcurrentHashMap.newKeySet()

    @Volatile
    var prewarmed = false
        private set

    fun start(keys: Collection<SnapshotKey>): CompletableFuture<Void> {
        val done = CompletableFuture<Void>()
        pending.add(done)
        done.whenComplete { _, _ -> pending.remove(done) }
        attempt(keys.toList(), context.options.prewarmInitialBackoff, done)
        return done
    }

    private fun attempt(keys: List<SnapshotKey>, backoff: Duration, done: CompletableFuture<Void>) {
        if (context.closed.get()) {
            done.completeExceptionally(context.closedError())
            return
        }
        val loads = keys.map { usableOf(it) }
        CompletableFuture.allOf(*loads.toTypedArray()).whenComplete { _, _ ->
            if (context.closed.get()) {
                done.completeExceptionally(context.closedError())
                return@whenComplete
            }
            val answered = loads.filterNot { it.isCompletedExceptionally }.map { it.join() }
            val failed = answered.filter { it.state is DocumentState.Failed }
            if (failed.isEmpty() && answered.size == loads.size) {
                prewarmed = true
                done.complete(null)
                return@whenComplete
            }
            context.threads.offRequestThread {
                log.warn { "Snapshot prewarm failed for ${failed.map { it.key }}; retrying in $backoff." }
            }
            val nextBackoff = minOf(backoff.multipliedBy(2), context.options.prewarmMaxBackoff)
            try {
                context.threads.scheduler.schedule(
                    { attempt(keys, nextBackoff, done) },
                    backoff.toMillis(),
                    TimeUnit.MILLISECONDS,
                )
            } catch (rejected: RejectedExecutionException) {
                done.completeExceptionally(rejected)
            }
        }
    }

    /** Answers every prewarm still waiting: the source is closed. */
    fun abort() {
        pending.forEach { it.completeExceptionally(context.closedError()) }
    }
}
