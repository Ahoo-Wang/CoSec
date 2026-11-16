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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import java.time.Duration

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
