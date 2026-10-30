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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.MovedHead
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * [context], keeping the digest of every head it served first, and of the app list: what one check of a candidate read
 * (RC1-33).
 */
internal class RecordingContext(private val context: ChangeChecks.Context) : ChangeChecks.Context by context {
    val read = LinkedHashMap<SnapshotKey, SnapshotDigest?>()

    /** The app list as first read; `null` until it is. */
    var apps: CheckedApps? = null
        private set

    override suspend fun head(key: SnapshotKey): Head? = context.head(key).also { read.putIfAbsent(key, it?.digest) }

    override suspend fun apps(): Versioned<AppList>? = context.apps().also {
        if (apps == null) apps = CheckedApps(it?.digest)
    }
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
    private val store: ControlStore,
    private val authority: ChangeAuthority,
    private val context: ChangeChecks.Context,
    private val clock: Clock,
    /** How long the detection may take: an order above the analysis budget (RFC 0007 §14.2), for its reads. */
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {
    /**
     * Records with [audit] what [detect] finds about [published]: the publish has happened whatever it finds, so a
     * failure is only logged. It runs to the end even when the caller is cancelled (a client that disconnects), within
     * [timeout]; one that does not finish in time is recorded as [ChangeEvent.Kind.REVIEW_NEEDED] with the escalation
     * [Escalation.DETECTION_TIMED_OUT], so it is told apart from a head that moved. The event is delivered as every
     * event but the gated ones, best effort: a REQUIRED listener could not undo the write, so it does not wait for one.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun record(
        published: ChangeRequest,
        rechecked: ChangeRequest,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        publisher: Operator,
        audit: suspend (HeadsMoved) -> Unit,
    ) = withContext(NonCancellable) {
        try {
            val outcome = withTimeoutOrNull(timeout) {
                Outcome(detect(published, rechecked, base, candidate, publisher))
            }
            val found = when (outcome) {
                null -> HeadsMoved(
                    ChangeEvent.Kind.REVIEW_NEEDED,
                    emptyList(),
                    listOf(Escalation(published.key.toString(), Escalation.DETECTION_TIMED_OUT, "within $timeout")),
                    "the heads could not be compared within $timeout"
                )

                else -> outcome.found ?: return@withContext
            }
            if (found.kind == ChangeEvent.Kind.REVIEW_NEEDED) {
                log.warn { "Change ${published.id.value} published on [${published.key}]: ${found.note}; moved ${found.moved}." }
            }
            audit(found)
        } catch (e: Exception) {
            log.warn(e) { "Whether the heads change ${published.id.value} was checked with moved could not be told." }
        }
    }

    /** What [detect] answered, so that "nothing moved" is told apart from a timeout. */
    private class Outcome(val found: HeadsMoved?)

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
        val heads = rechecked.checkedWith.filterKeys { it != published.key }
            .mapNotNull { (key, checked) -> moved(key.toString(), checked) { store.head(key)?.digest } }
        val apps = rechecked.checkedApps?.let { checked -> moved(APP_LIST, checked.digest) { store.apps()?.digest } }
        val moved = heads + listOfNotNull(apps)
        if (moved.isEmpty()) {
            return null
        }
        return analyzed(published, rechecked, base, candidate, publisher, moved)
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
        val unapproved = now.filter { !it.informational && it.subject !in approved }
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

    /**
     * [name] as a [MovedHead] when what [current] reads is not [checked] any more, or cannot be read; `null` when it is
     * the same. Only digests are compared: the store's own, never a decoded document.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun moved(
        name: String,
        checked: SnapshotDigest?,
        current: suspend () -> SnapshotDigest?
    ): MovedHead? {
        val now = try {
            current()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[$name] could not be read after a publish; it counts as moved." }
            return MovedHead(name, checked?.toString(), null, readable = false)
        }
        return if (now == checked) null else MovedHead(name, checked?.toString(), now?.toString())
    }

    companion object {
        /** How [MovedHead.document] names the app list (RC1-4). */
        const val APP_LIST = "list:apps"
        val DEFAULT_TIMEOUT: Duration = 1.seconds
    }
}
