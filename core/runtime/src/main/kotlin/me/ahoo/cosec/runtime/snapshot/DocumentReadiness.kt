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

    /** Loads the platform document, then the [prewarm] ones, retrying those until each is installed. Never blocks. */
    fun start() {
        source.load().whenComplete { _, _ -> source.prewarm(prewarm) }
    }

    /**
     * The documents not installed now: the platform's while it is not present, and, until the prewarm has completed
     * once, each prewarmed document with no entry or a failed one. Empty means ready.
     */
    fun missing(): Set<SnapshotKey> = buildSet {
        if (source.peek()?.policies !is DocumentState.Present<*>) {
            add(SnapshotKey.Platform)
        }
        if (!source.isPrewarmed && prewarm.isNotEmpty()) {
            val installed = source.entries().associateBy { it.key }
            prewarm.filterTo(this) { key -> installed[key]?.state.let { it == null || it is DocumentState.Failed } }
        }
    }

    /**
     * Waits until the platform and every prewarmed document have been read, installed or failed, and answers [missing].
     * Cancelling the wait cancels nothing of the loads ([SnapshotSource.settled]).
     */
    suspend fun await(): Set<SnapshotKey> {
        source.settled(prewarm).await()
        return missing()
    }
}
