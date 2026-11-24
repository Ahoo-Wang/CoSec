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

package me.ahoo.cosec.control.lifecycle

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.audit.RevisionRef
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.RevisionEntry
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoredDocument
import me.ahoo.cosec.control.store.lostRevisions
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

/**
 * What the control plane does of a published revision whose head write was lost (`CTL-86`, DR1-F-7): a revision not on
 * its head's chain, as a Redis failover leaves one. The control plane holds the facts that say so in two places, and
 * tells from both:
 * - the reclamation pass finds the revisions an index lists above its head ([check]), for a document nobody reads;
 * - a rebuild of an index from its head (`HistoryRepair`) hands over the entries it is about to drop ([dropped]): once a
 *   publish reused the number, or a read rebuilt the index, they are listed no more, and that is the last chance.
 *
 * Each is logged at `ERROR`, told to the change listeners ([ChangeEvent.Kind.HEAD_LOST], naming the change from the
 * revision's own provenance) and counted ([found]), once by this instance, by document and digest.
 */
internal class LostHeads(
    private val store: ControlStore,
    private val revisions: Revisions,
    private val listeners: ChangeListeners,
    private val clock: Clock,
) {
    /** The revisions this instance has told of, by document and digest: each once, however many passes find it. */
    private val told = ConcurrentHashMap.newKeySet<String>()

    /** Lost revisions this instance found since it started: `cosec.control.revisions.lost`. */
    val found = AtomicLong()

    /** Logs, counts and tells of each revision [key]'s index lists above its head, once by this instance. */
    suspend fun check(key: SnapshotKey) {
        val lost = store.lostRevisions(key)
        if (lost.isEmpty()) return
        val head = store.head(key) ?: return
        tellOnce(key, head, lost)
    }

    /**
     * The entries a rebuild of [key]'s index from [head] is about to drop, not on its chain: told as [check] tells. A
     * failure is only logged: the read that rebuilds goes on, and the reclamation pass looks again while they are listed.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun dropped(key: SnapshotKey, head: StoredDocument, entries: List<RevisionEntry>) {
        try {
            tellOnce(key, head, entries)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[$key]: the revisions a rebuild of its index drops could not be told of (CTL-86)." }
        }
    }

    private suspend fun tellOnce(key: SnapshotKey, head: StoredDocument, entries: List<RevisionEntry>) =
        entries.filter { told.add("$key@${it.digest}") }.forEach {
            found.incrementAndGet()
            tell(key, head.document.revision, head.digest.toString(), it)
        }

    private suspend fun tell(key: SnapshotKey, headRevision: Long, headDigest: String, entry: RevisionEntry) {
        val provenance = store.record(key, entry.digest)?.let { revisions.decode(key, it) }?.provenance
        log.error {
            "[$key] revision ${entry.revision} (${entry.digest}) was published and is not on the head's chain, now at " +
                "revision $headRevision: its head write was lost (a Redis failover?), and change " +
                "${provenance?.change ?: "unknown"} is not in effect. Publish it again or roll back (CTL-86)."
        }
        provenance ?: return
        listeners.publish(
            ChangeEvent(
                id = "${provenance.change}:${ChangeEvent.Kind.HEAD_LOST.name}:${clock.millis()}",
                time = clock.instant(),
                kind = ChangeEvent.Kind.HEAD_LOST,
                operator = provenance.publishedBy,
                change = ChangeId(provenance.change),
                document = key,
                base = RevisionRef(headRevision, headDigest),
                candidate = RevisionRef(entry.revision, entry.digest.toString()),
                note = "head write lost: revision ${entry.revision} published, the head is at $headRevision",
            ),
        )
    }
}
