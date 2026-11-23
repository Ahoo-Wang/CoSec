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
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.audit.RevisionRef
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.RevisionEntry
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.lostRevisions
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

/**
 * What the reclamation pass does of a document whose head is behind its index (`CTL-86`, DR1-F-7): the revisions above
 * the head were published and then lost, and the control plane holds the facts that say so. Each is logged at `ERROR`,
 * told to the change listeners once ([ChangeEvent.Kind.HEAD_LOST], naming the change from the revision's own
 * provenance), and counted ([found]). The same pass then reads the document's history, which rebuilds its index from the
 * head and lists them no more: the signal is given once, and the instance that gives it is the one holding the pass.
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

    /** Logs, counts and tells of each revision [key] lost, once by this instance. */
    suspend fun check(key: SnapshotKey) {
        val lost = store.lostRevisions(key)
        if (lost.isEmpty()) return
        val head = store.head(key) ?: return
        lost.filter { told.add("$key@${it.digest}") }.forEach {
            found.incrementAndGet()
            tell(key, head.document.revision, head.digest.toString(), it)
        }
    }

    private suspend fun tell(key: SnapshotKey, headRevision: Long, headDigest: String, entry: RevisionEntry) {
        val provenance = store.record(key, entry.digest)?.let { revisions.decode(key, it) }?.provenance
        log.error {
            "[$key] revision ${entry.revision} is listed above the head at revision $headRevision, and nothing published " +
                "over it: its head write was lost (a Redis failover?), and change ${provenance?.change ?: "unknown"} is not " +
                "in effect. Publish it again or roll back (CTL-86)."
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
