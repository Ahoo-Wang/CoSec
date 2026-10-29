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

package me.ahoo.cosec.control.store

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Changes
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
 *   cannot read every open change reclaims nothing: it would not know what they rest on;
 * - [closeShadowWindows] looks at every shadowing change ([Changes.shadowed], RFC 0007 §6.4) under the
 *   [SHADOW_LEASE], so one instance does it per period. The pass is bounded by [shadowInterval] and the lease lasts
 *   twice that, so it never outlives the lease; it is released when the pass ends.
 *
 * Changes are read through [Changes.get], which expires one past `cosec.control.changes.expire-after` (RFC 0007
 * §6.1): so a change nobody finishes still leaves the open list. A failure on one document or change is logged and
 * the pass goes on with the next; the next pass tries again.
 */
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

    /** One reclamation pass; what was removed, by document. Nothing when an open change cannot be read. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun reclaim(): Map<SnapshotKey, Reclaimed> {
        val open = try {
            openChanges()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            log.warn(e) { "Could not read the open changes; no revision is reclaimed this pass." }
            return emptyMap()
        } catch (e: Exception) {
            log.warn(e) { "Could not read the open changes; no revision is reclaimed this pass." }
            return emptyMap()
        }
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
        if (!store.acquireLease(SHADOW_LEASE, holder, shadowInterval.multipliedBy(2))) {
            return emptyList()
        }
        try {
            return withTimeout(shadowInterval.toKotlinDuration()) {
                val shadowing = store.openChanges().mapNotNull { id ->
                    attempt("read change [${id.value}]") { current(id) }?.takeIf { it.state == ChangeState.SHADOWING }
                }
                shadowing.mapNotNull { change ->
                    attempt("look at the shadow window of change [${change.id.value}]") { changes.shadowed(change.id) }
                        ?.takeIf { it.state != ChangeState.SHADOWING }
                }
            }
        } finally {
            withContext(NonCancellable) {
                attempt("release the shadow lease") { store.releaseLease(SHADOW_LEASE, holder) }
            }
        }
    }

    /** Every open change as it is now; throws when one cannot be read. */
    private suspend fun openChanges(): List<ChangeRequest> =
        store.openChanges().mapNotNull { current(it) }.filterNot { it.state.terminal }

    /** [id] as it is now, expired if it is past its time; null when it is not written yet or no longer exists. */
    private suspend fun current(id: ChangeId): ChangeRequest? = try {
        changes.get(id)
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

    companion object {
        const val SHADOW_LEASE = "shadow-windows"
        private const val NOT_FOUND = 404
    }
}
