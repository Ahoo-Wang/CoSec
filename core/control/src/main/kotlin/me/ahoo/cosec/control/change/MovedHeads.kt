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

package me.ahoo.cosec.control.change

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.MovedHead
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/** [context], keeping the digest of every head it served first: what one check of a candidate read (RC1-33). */
internal class RecordingContext(private val context: ChangeChecks.Context) : ChangeChecks.Context by context {
    val read = LinkedHashMap<SnapshotKey, SnapshotDigest?>()

    override suspend fun head(key: SnapshotKey): Head? = context.head(key).also { read.putIfAbsent(key, it?.digest) }
}

/** What [MovedHeads.detect] found once a change was published: the event to record (RC1-33). */
internal class HeadsMoved(
    val kind: ChangeEvent.Kind,
    val moved: List<MovedHead>,
    val escalations: List<Escalation>,
    val note: String,
)

/**
 * After a publish (RC1-33). The publish compare-and-sets only the target head: the heads of the other documents the
 * last check read ([ChangeRequest.checkedWith]) are separate keys, and no single write covers keys of different Redis
 * Cluster slots, so one of them may move between that check and the write. Each is read again; when one moved, or
 * cannot be read, the grant analysis runs again on the current heads: [ChangeEvent.Kind.HEADS_MOVED] when it finds
 * nothing the publish did not have approved, [ChangeEvent.Kind.REVIEW_NEEDED] when it does, or cannot run.
 *
 * Nothing is rolled back. Restricting a grantor never withdraws a grant it made before, so a restriction published
 * meanwhile and this grant come out as the serial order "grant, then restriction". The one outcome no serial order
 * gives is two changes that each remove a right the other's grant rests on, landing within the window; the event is
 * how it is seen, and a person reviews it.
 */
internal class MovedHeads(
    private val revisions: Revisions,
    private val authority: ChangeAuthority,
    private val context: ChangeChecks.Context,
    private val clock: Clock,
) {
    /**
     * Records with [audit] what [detect] finds about [published]: the publish has happened whatever it finds, so a
     * failure is only logged. The event is delivered as every event but the gated ones, best effort: a REQUIRED
     * listener could not undo the write, so it does not wait for one.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun record(
        published: ChangeRequest,
        rechecked: ChangeRequest,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        publisher: Operator,
        audit: suspend (HeadsMoved) -> Unit,
    ) {
        try {
            detect(published, rechecked, base, candidate, publisher)?.let { audit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Whether the heads change ${published.id.value} was checked with moved could not be told." }
        }
    }

    /**
     * What to record about [published], whose last check was [rechecked] of [candidate] over [base] by [publisher];
     * `null` when every head it was checked with is the one it had.
     */
    suspend fun detect(
        published: ChangeRequest,
        rechecked: ChangeRequest,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        publisher: Operator,
    ): HeadsMoved? {
        val moved = rechecked.checkedWith.filterKeys { it != published.key }
            .mapNotNull { (key, checked) -> moved(key, checked) }
        if (moved.isEmpty()) {
            return null
        }
        val found = analyzed(published, rechecked, base, candidate, publisher, moved)
        if (found.kind == ChangeEvent.Kind.REVIEW_NEEDED) {
            log.warn { "Change ${published.id.value} published on [${published.key}] while $moved moved: ${found.note}." }
        }
        return found
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun analyzed(
        published: ChangeRequest,
        rechecked: ChangeRequest,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        publisher: Operator,
        moved: List<MovedHead>,
    ): HeadsMoved = try {
        val now = authority.escalations(
            publisher,
            published.key,
            published.operations,
            base,
            candidate,
            context,
            clock.instant(),
        )
        val approved = rechecked.escalations.map { it.subject }.toSet()
        val unapproved = now.filter { it.subject !in approved }
        if (unapproved.isEmpty()) {
            HeadsMoved(ChangeEvent.Kind.HEADS_MOVED, moved, now, "the analysis on the current heads finds nothing new")
        } else {
            HeadsMoved(
                ChangeEvent.Kind.REVIEW_NEEDED,
                moved,
                now,
                "the analysis on the current heads finds ${unapproved.size} escalation(s) the publish did not have " +
                    "approved: ${unapproved.joinToString("; ") { it.subject }}",
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "The analysis of change ${published.id.value} could not run again after its publish." }
        HeadsMoved(ChangeEvent.Kind.REVIEW_NEEDED, moved, emptyList(), "the analysis could not run again: ${e.message}")
    }

    /** [key] as a [MovedHead] when its head is not [checked] any more, or cannot be read; `null` when it is the same. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun moved(key: SnapshotKey, checked: SnapshotDigest?): MovedHead? {
        val current = try {
            revisions.head(key)?.digest
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "The head of [$key] could not be read after a publish; it counts as moved." }
            return MovedHead(key, checked?.toString(), null, readable = false)
        }
        return if (current == checked) null else MovedHead(key, checked?.toString(), current?.toString())
    }
}
