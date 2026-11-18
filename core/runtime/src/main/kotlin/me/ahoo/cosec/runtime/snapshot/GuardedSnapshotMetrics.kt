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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import java.time.Duration

/**
 * Calls [delegate] and swallows what it throws, errors included: a failing meter must never fail a load, lose a reload
 * or break single-flight. Only a [VirtualMachineError] passes through; [SnapshotSource] lets it out only after the load
 * it interrupted has finished. What a meter throws is counted in [refusals], the housekeeping summary's own state, and
 * never written or offered to housekeeping on the thread that reported the event (`SNP-19`): a meter that throws at
 * every event would fill housekeeping by itself, and its own refusal would be reported to it again.
 */
@Suppress("TooGenericExceptionCaught")
internal class GuardedSnapshotMetrics(
    private val delegate: SnapshotMetrics,
    val refusals: HousekeepingRefusals = HousekeepingRefusals(),
) : SnapshotMetrics {
    private inline fun guard(event: String, call: () -> Unit) {
        try {
            call()
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            refusals.meterFailed("SnapshotMetrics.$event", error)
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

    override fun onEvicted(kind: SnapshotKind, absent: Boolean) =
        guard("onEvicted") { delegate.onEvicted(kind, absent) }

    override fun onHandOffRefused(ranInPlace: Boolean) = guard(
        "onHandOffRefused"
    ) { delegate.onHandOffRefused(ranInPlace) }

    override fun onHousekeepingRefused(task: HousekeepingTask) =
        guard("onHousekeepingRefused") { delegate.onHousekeepingRefused(task) }
}
