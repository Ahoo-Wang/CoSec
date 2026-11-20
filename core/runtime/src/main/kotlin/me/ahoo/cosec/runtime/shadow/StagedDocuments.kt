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

package me.ahoo.cosec.runtime.shadow

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.spi.shadow.CandidateStore
import me.ahoo.cosec.spi.shadow.StagedCandidates
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * The staged list as this instance last read it (`CTL-47`): which documents may stage a candidate, asked on the request
 * path and refreshed off it ([refresh], every `report-interval` by the shadow reporter).
 *
 * - **A list:** only a listed document's candidate is read, and only a request whose view reads a listed document is
 *   sampled. With nothing listed, no request is sampled and no candidate read: the question is one volatile read and a
 *   size check, so with no window open a request pays nothing for shadowing.
 * - **No list kept** (the store answers null: no list, or a control plane that does not keep one): every document may
 *   stage one, read by its key, as before the list.
 * - **A list that cannot be read**, and the time before the first read, count as no list: never as nothing staged.
 *
 * **How stale it may be:** one refresh interval, plus the read. It only gates; the candidate is still read by its key and
 * checked by its window. A window opened since the last read is not sampled until the next one: samples come later. A
 * window closed since is still asked for: its candidate is read and is gone or of another window, so nothing is
 * sampled. Either way a staleness costs samples, never a sample of the wrong candidate.
 */
@InternalCoSecApi
class StagedDocuments(private val store: CandidateStore, private val clock: Clock = Clock.systemUTC()) {
    /** The list read, as the strings a request names its documents by; null for no list. */
    private class Listed(val platform: Boolean, val apps: Set<String>, val tenants: Set<String>) {
        val empty: Boolean = !platform && apps.isEmpty() && tenants.isEmpty()
    }

    @Volatile
    private var listed: Listed? = null

    /** When the list was last read; null while none could be. */
    @Volatile
    var readAt: Instant? = null
        private set

    /** Whether a list is kept and was read: only then does it gate anything. */
    val gates: Boolean get() = listed != null

    /** Whether [key]'s candidate may be staged: read it by its key. */
    fun mayStage(key: SnapshotKey): Boolean {
        val list = listed ?: return true
        return when (key) {
            SnapshotKey.Platform -> list.platform
            is SnapshotKey.App -> key.appId in list.apps
            is SnapshotKey.Tenant -> key.tenantId in list.tenants
        }
    }

    /** Whether a request in [scope], decided on [view], reads a document that may stage a candidate. */
    fun mayStage(scope: Scope, view: SnapshotView): Boolean {
        val list = listed ?: return true
        if (list.empty) return false
        if (list.platform) return true
        return listed(list.apps, view.app?.owner, scope.app.value) || listed(list.tenants, view.tenant?.owner, scope.tenant.value)
    }

    private fun listed(ids: Set<String>, viewed: String?, scoped: String): Boolean =
        ids.isNotEmpty() && (scoped in ids || (viewed != null && viewed in ids))

    /** Reads the list again; one that cannot be read counts as none until it can, never as nothing staged. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun refresh() {
        val read = try {
            store.staged()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            unreadable(e)
            return
        } catch (e: Exception) {
            unreadable(e)
            return
        }
        listed = read?.let(::listedOf)
        readAt = clock.instant()
    }

    private fun unreadable(error: Exception) {
        val was = readAt
        listed = null
        readAt = null
        // Once per outage: the line says the list was readable and is not, then the debug log has each failure.
        if (was != null) {
            log.warn { "The staged list cannot be read since it was at $was: candidates are read by their keys until it can." }
        }
        log.debug(error) { "The staged list cannot be read." }
    }

    private fun listedOf(candidates: StagedCandidates): Listed {
        var platform = false
        val apps = HashSet<String>()
        val tenants = HashSet<String>()
        for (key in candidates.windows.keys) {
            when (key) {
                SnapshotKey.Platform -> platform = true
                is SnapshotKey.App -> apps += key.appId
                is SnapshotKey.Tenant -> tenants += key.tenantId
            }
        }
        return Listed(platform, apps, tenants)
    }
}
