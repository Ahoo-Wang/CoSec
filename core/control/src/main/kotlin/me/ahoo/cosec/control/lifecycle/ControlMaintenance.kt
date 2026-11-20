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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.ControlStatus.NOT_FOUND
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.ListedChange
import me.ahoo.cosec.control.store.Reclaimed
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.toKotlinDuration

private val log = KotlinLogging.logger {}

/**
 * The control plane's periodic tasks; the host calls each at its own interval, on every instance:
 *
 * - [reclaim] removes the revision records retention no longer keeps (RFC 0007 §5.7), document by document, keeping
 *   what open changes rest on. [Revisions.reclaim] takes a lease per document, so one instance does each. A pass that
 *   cannot read every open change reclaims nothing: it would not know what they rest on. Each pass first runs
 *   [reconcileApps], whatever the open changes;
 * - [reconcileApps], also once at startup, marks a complete app list incomplete again when it misses an app document
 *   (RC1-4);
 * - [closeShadowWindows] looks at every shadowing change ([Changes.shadowed], RFC 0007 §6.4) under the
 *   [SHADOW_LEASE], so one instance does it per period, then reconciles the staged list (`CTL-46`). The pass is bounded by [shadowInterval] and the lease lasts
 *   twice that, so it never outlives the lease; it is released when the pass ends.
 *
 * The open changes are read once, by [ControlStore.openChanges], and a pass goes by that read (#1219). One past
 * `cosec.control.changes.expire-after` (RFC 0007 §6.1) is expired through [Changes.current]: so a change nobody
 * finishes still leaves the open list. A failure on one document or change is logged and the pass goes on with the
 * next; the next pass tries again.
 */
@InternalCoSecApi
class ControlMaintenance(
    private val store: ControlStore,
    private val revisions: Revisions,
    private val changes: Changes,
    /** The host's interval between shadow passes (`cosec.control.shadow.check-interval`). */
    private val shadowInterval: Duration,
) {
    init {
        require(shadowInterval > Duration.ZERO) { "The shadow interval must be positive, not $shadowInterval." }
    }

    private val holder: String = UUID.randomUUID().toString()

    private val stagedList = StagedListReconciliation(store)

    /**
     * One reclamation pass; what was removed, by document. Nothing while the open-change sets are not kept (`CTL-54`):
     * an open change only the list of the format before names could rest on a revision. A document one of whose open
     * changes cannot be read is skipped this pass; the others are reclaimed (R2C-2).
     */
    suspend fun reclaim(): Map<SnapshotKey, Reclaimed> {
        // One read of the documents for both: they are one large value (B1 review).
        val documents = attempt("list the documents") { store.documents() } ?: return emptyMap()
        attempt("reconcile the app list with the documents") { reconcileApps(documents) }
        attempt("keep the open-change sets") { store.keepOpenChanges() }
        if (attempt("read whether the open-change sets are kept") { store.openChangesKept() } != true) {
            log.warn {
                "The open-change sets are not kept (a list of the format before 8.0.0-beta.5 is there): no revision is " +
                    "reclaimed until `cosec-migrate repair-references` converts it."
            }
            return emptyMap()
        }
        val reclaimed = LinkedHashMap<SnapshotKey, Reclaimed>()
        for (key in documents) {
            val keep = attempt("read the open changes of [$key]") { resting(key) } ?: continue
            attempt("reclaim the revisions of [$key]") { revisions.reclaim(key, keep) }
                ?.takeIf { it.any }
                ?.let { reclaimed[key] = it }
        }
        return reclaimed
    }

    /**
     * What the open changes of [key] rest on, each as it is now (expired if past its time); throws when one cannot be
     * read, since it might rest on a revision. The store read each of them once to list it (#1219).
     */
    private suspend fun resting(
        key: SnapshotKey
    ): Set<SnapshotDigest> = store.openChanges(key).flatMapTo(HashSet()) { listed ->
        when (listed) {
            is ListedChange.Unreadable -> throw IllegalStateException(
                "change [${listed.id.value}] of [$key] does not read"
            )
            is ListedChange.Readable -> current(listed.change)?.takeUnless { it.state.terminal }?.restsOn().orEmpty()
        }
    }

    /**
     * Marks a complete app list incomplete again when the store lists an app document it misses (RC1-4,
     * [Revisions.reconcileApps]); the apps it misses. The host runs it at startup, so that a list a downgraded writer
     * left stale is not trusted until the first reclamation pass, and every reclamation pass runs it first.
     */
    suspend fun reconcileApps(): Set<String> = reconcileApps(store.documents())

    private suspend fun reconcileApps(documents: Set<SnapshotKey>): Set<String> =
        revisions.reconcileApps(documents).also { changes.appListReconciled() }

    /**
     * Makes the staged list kept (`CTL-46`, [ControlStore.keepStagedList]): the host runs it at startup, so that a store
     * no control plane kept a list in gets one before the first shadow pass, which keeps it too.
     */
    suspend fun keepStagedList() = store.keepStagedList()

    /**
     * One pass over the shadowing changes; the changes whose window closed or conflicted. Nothing without the lease.
     * Each pass then reconciles the staged list with what is staged ([StagedListReconciliation]).
     */
    suspend fun closeShadowWindows(): List<ChangeRequest> {
        // A store that cannot answer is logged and the pass does nothing (CTL-52), as every other read of it here.
        val leased = attempt("take the shadow lease") {
            store.acquireLease(SHADOW_LEASE, holder, shadowInterval.multipliedBy(2))
        }
        if (leased != true) {
            return emptyList()
        }
        try {
            return withTimeout(shadowInterval.toKotlinDuration()) {
                attempt("keep the open-change sets") { store.keepOpenChanges() }
                val read = shadowingChanges().map { listed ->
                    (listed as? ListedChange.Readable)?.let {
                        attempt(
                            "read change [${it.id.value}]"
                        ) { listOfNotNull(current(it.change)) }
                    }
                }
                val shadowing = read.flatMap { it.orEmpty() }.filter { it.state == ChangeState.SHADOWING }
                val closed = shadowing.mapNotNull { change ->
                    attempt("look at the shadow window of change [${change.id.value}]") { changes.shadowed(change.id) }
                        ?.takeIf { it.state != ChangeState.SHADOWING }
                }
                attempt("reconcile the staged list") { stagedList.reconcile(shadowing, read.none { it == null }) }
                closed
            }
        } finally {
            withContext(NonCancellable) {
                attempt("release the shadow lease") { store.releaseLease(SHADOW_LEASE, holder) }
            }
        }
    }

    /** Every change listed as shadowing, page by page (R2C-2): the pass no longer reads every open change. */
    private suspend fun shadowingChanges(): List<ListedChange> {
        val found = ArrayList<ListedChange>()
        var after: String? = null
        do {
            val page = store.changes(ChangeState.SHADOWING, after, SHADOW_PAGE)
            found += page.changes
            after = page.next
        } while (after != null)
        return found
    }

    /**
     * [change] as it is now, expired if it is past its time; null when it no longer exists. One that cannot be
     * expired stops the pass: it might be a change that rests on a revision.
     */
    private suspend fun current(change: ChangeRequest): ChangeRequest? = try {
        changes.current(change)
    } catch (e: ChangeRejectedException) {
        if (e.status != NOT_FOUND) throw e
        null
    }

    /** The revisions [this] change rests on: its base, and what its last check read of its own document. */
    private fun ChangeRequest.restsOn(): Set<SnapshotDigest> =
        setOfNotNull(base.digest, checkedWith[key])

    /** [block], or null when it fails; a cancellation of the pass itself still ends it. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        log.warn(e) { "Could not $what; the next pass tries again." }
        null
    } catch (e: Exception) {
        log.warn(e) { "Could not $what; the next pass tries again." }
        null
    }

    @InternalCoSecApi
    companion object {
        const val SHADOW_LEASE = "shadow-windows"

        /** How many shadowing changes the pass reads a page. */
        private const val SHADOW_PAGE = 200
    }
}
