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

package me.ahoo.cosec.control.store

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.snapshot.SnapshotDigest
import me.ahoo.cosec.snapshot.SnapshotKey
import java.time.Duration
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * The control plane's periodic tasks; the host calls each at its own interval, on every instance:
 *
 * - [reclaim] removes the revision records retention no longer keeps (RFC 0007 §5.7), document by document, keeping
 *   what open changes rest on. [Revisions.reclaim] takes a lease per document, so one instance does each;
 * - [closeShadowWindows] looks at every shadowing change ([Changes.shadowed], RFC 0007 §6.4) under a lease of
 *   [shadowLease], so one instance does it per period.
 *
 * Each pass reads what the store lists ([ControlStore.documents], [ControlStore.openChanges]) and then each change's
 * own state, since a listed change may have moved on. A failure on one document or change is logged and the pass goes
 * on with the next; the next pass tries again.
 */
class ControlMaintenance(
    private val store: ControlStore,
    private val revisions: Revisions,
    private val changes: Changes,
    /** How long one instance holds the shadow pass; the host's interval between passes. */
    private val shadowLease: Duration,
) {
    private val holder: String = UUID.randomUUID().toString()

    /** One reclamation pass; what was removed, by document. */
    suspend fun reclaim(): Map<SnapshotKey, Reclaimed> {
        val open = openChanges()
        val reclaimed = LinkedHashMap<SnapshotKey, Reclaimed>()
        for (key in store.documents()) {
            val keep = open.filter { it.key == key }.flatMapTo(HashSet()) { it.restsOn() }
            attempt("reclaim the revisions of [$key]") { revisions.reclaim(key, keep) }
                ?.takeIf { it.records.isNotEmpty() || it.orphans.isNotEmpty() }
                ?.let { reclaimed[key] = it }
        }
        return reclaimed
    }

    /** One pass over the shadowing changes; the changes whose window closed or conflicted. Nothing without the lease. */
    suspend fun closeShadowWindows(): List<ChangeRequest> {
        if (!store.acquireLease(SHADOW_LEASE, holder, shadowLease)) {
            return emptyList()
        }
        return openChanges().filter { it.state == ChangeState.SHADOWING }.mapNotNull { change ->
            attempt("look at the shadow window of change [${change.id.value}]") { changes.shadowed(change.id) }
                ?.takeIf { it.state != ChangeState.SHADOWING }
        }
    }

    /** The open changes as they are now; one that cannot be read is left out of this pass. */
    private suspend fun openChanges(): List<ChangeRequest> = store.openChanges().mapNotNull { id ->
        attempt("read change [${id.value}]") { store.change(id)?.value }?.takeUnless { it.state.terminal }
    }

    /** The revisions [this] change rests on: its base, and what its last check read of its own document. */
    private fun ChangeRequest.restsOn(): Set<SnapshotDigest> =
        setOfNotNull(base.digest, checkedWith[key])

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "Could not $what; the next pass tries again." }
        null
    }

    companion object {
        const val SHADOW_LEASE = "shadow-windows"
    }
}
