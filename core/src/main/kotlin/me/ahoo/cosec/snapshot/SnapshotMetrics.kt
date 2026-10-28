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

package me.ahoo.cosec.snapshot

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration

/**
 * Events of a [SnapshotSource], for meters such as RFC 0003 §4.5's `cosec.snapshot.*`. Gauges (installed documents,
 * lag per key) are read from [SnapshotSource.entries] instead.
 *
 * [onLoad] runs on the request path, on whatever thread calls [SnapshotSource.peek], so implementations must be cheap
 * and must not block. Tag by [SnapshotKind], never by app id: app ids are unbounded.
 */
interface SnapshotMetrics {
    /** One document a request needed: already installed ([LoadOutcome.HIT]) or loaded for it. */
    fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) = Unit

    fun onCompile(kind: SnapshotKind, duration: Duration, succeeded: Boolean) = Unit

    /** A new entry replaced the installed one, or filled an empty slot. */
    fun onInstall(entry: SnapshotEntry<*>) = Unit

    /**
     * The store answered with a revision lower than the last one installed under [key], or the same revision with
     * different content. The loaded document is installed anyway: the store is the record. In practice this means the
     * store lost an acknowledged write, as Redis does when a replica without it is promoted after a failover.
     */
    fun onRegression(key: SnapshotKey, installedRevision: Long, loadedRevision: Long) = Unit

    /** The store answered `Absent` for a document that had been seen; the key now fails closed. */
    fun onDocumentLost(key: SnapshotKey, lastRevision: Long) = Unit

    /** A document's lag passed [SnapshotOptions.lagWarn], or [SnapshotOptions.lagEscalate] when [escalated]. */
    fun onLagExceeded(key: SnapshotKey, lag: Duration, escalated: Boolean) = Unit

    companion object {
        val NOOP: SnapshotMetrics = object : SnapshotMetrics {}
    }
}

enum class LoadOutcome {
    /** Already installed. */
    HIT,

    /** Not installed; loaded for this request. */
    COLD,

    /** Not installed; the load failed, and the request sees a failed document. */
    FAILED,

    /** Not installed; the load did not finish within [SnapshotOptions.coldLoadTimeout]. */
    TIMEOUT,
}

/**
 * Calls [delegate] and swallows what it throws, errors included: a failing meter must never fail a load, lose a reload
 * or break single-flight. Only a [VirtualMachineError] passes through; [SnapshotSource] lets it out only after the load
 * it interrupted has finished.
 */
@Suppress("TooGenericExceptionCaught")
internal class GuardedSnapshotMetrics(private val delegate: SnapshotMetrics) : SnapshotMetrics {
    private inline fun guard(event: String, call: () -> Unit) {
        try {
            call()
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            log.warn { "SnapshotMetrics.$event failed: $error" }
        }
    }

    override fun onLoad(kind: SnapshotKind, outcome: LoadOutcome) = guard("onLoad") { delegate.onLoad(kind, outcome) }

    override fun onCompile(kind: SnapshotKind, duration: Duration, succeeded: Boolean) =
        guard("onCompile") { delegate.onCompile(kind, duration, succeeded) }

    override fun onInstall(entry: SnapshotEntry<*>) = guard("onInstall") { delegate.onInstall(entry) }

    override fun onRegression(key: SnapshotKey, installedRevision: Long, loadedRevision: Long) =
        guard("onRegression") { delegate.onRegression(key, installedRevision, loadedRevision) }

    override fun onDocumentLost(key: SnapshotKey, lastRevision: Long) =
        guard("onDocumentLost") { delegate.onDocumentLost(key, lastRevision) }

    override fun onLagExceeded(key: SnapshotKey, lag: Duration, escalated: Boolean) =
        guard("onLagExceeded") { delegate.onLagExceeded(key, lag, escalated) }

    private companion object {
        private val log = KotlinLogging.logger {}
    }
}
