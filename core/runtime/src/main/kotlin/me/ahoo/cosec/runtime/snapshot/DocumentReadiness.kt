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

import kotlinx.coroutines.future.await
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether the documents a host loads before taking traffic are installed (RFC 0003 §4.3, X6): the platform's, then those
 * of [prewarm]. One question for every host, asked of [source] without I/O: the starter's readiness probe and
 * `Pipeline.awaitDocuments` ask it alike. It changes no decision: a request for a document that is not installed is a
 * 503 whether or not anyone asks.
 */
@InternalCoSecApi
class DocumentReadiness(private val source: SnapshotSource<*, *>, prewarm: Collection<SnapshotKey>) {
    /** The prewarmed documents besides the platform's, each once. */
    private val prewarm: List<SnapshotKey> = prewarm.filter { it != SnapshotKey.Platform }.distinct()

    /** Documents a host names once its other beans exist (a gateway's routes, `SNP-20`), each with the prewarm loading it. */
    private class Later(val keys: List<SnapshotKey>, val loaded: CompletableFuture<Void>)

    private val later = CopyOnWriteArrayList<Later>()

    /** The prewarm [start] runs, once its first read of the platform document has ended; null before. */
    @Volatile
    private var first: CompletableFuture<Void>? = null

    /**
     * Loads the platform document, then prewarms it with the [prewarm] ones: each is read again, with backoff, until it
     * is installed and not failed. Never blocks. So a first read that failed (the store down, restarting or loading) is
     * read again without a request, which a host that is not ready never receives: it becomes ready once the store
     * answers (`SNP-21`).
     */
    fun start() {
        source.load().whenComplete { _, _ -> first = source.prewarm(listOf(SnapshotKey.Platform) + prewarm) }
    }

    /**
     * Prewarms [keys] too, the way [start] prewarms the first ones: readiness waits for each until its prewarm has
     * completed once (`SNP-20`). For documents a host names only once its other beans exist; call it before the host
     * reports ready. Keys already prewarmed here are left out.
     */
    fun prewarmAlso(keys: Collection<SnapshotKey>) {
        val more = keys.filter { key -> key != SnapshotKey.Platform && key !in prewarm && later.none { key in it.keys } }
            .distinct()
        if (more.isNotEmpty()) {
            later += Later(more, source.prewarm(more))
        }
    }

    /**
     * The documents not installed now: the platform's while it is not present, and, until their prewarm has completed
     * once, each prewarmed document with no entry or a failed one. Empty means ready.
     */
    fun missing(): Set<SnapshotKey> = buildSet {
        if (source.peek()?.policies !is DocumentState.Present<*>) {
            add(SnapshotKey.Platform)
        }
        // Its own prewarm's, not the source's flag, which any prewarm completing sets (a gateway's route apps).
        val waiting = (if (first?.isDone == true) emptyList() else prewarm) +
            later.filterNot { it.loaded.isDone }.flatMap { it.keys }
        if (waiting.isNotEmpty()) {
            val installed = source.entries().associateBy { it.key }
            waiting.filterTo(this) { key -> installed[key]?.state.let { it == null || it is DocumentState.Failed } }
        }
    }

    /**
     * Waits until the platform and every prewarmed document have been read, installed or failed, and answers [missing].
     * Cancelling the wait cancels nothing of the loads ([SnapshotSource.settled]).
     */
    suspend fun await(): Set<SnapshotKey> {
        source.settled(prewarm + later.flatMap { it.keys }).await()
        return missing()
    }
}
