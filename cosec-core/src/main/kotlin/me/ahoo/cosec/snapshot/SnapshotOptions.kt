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

import java.time.Duration

/**
 * Tuning of a [SnapshotSource]. Defaults are the values RFC 0003 §12 decided (Q7, Q18, Q19); the ones marked there as
 * "confirmed after measurement" may change once §4.8 has real numbers.
 */
data class SnapshotOptions(
    /** How often every installed document is revalidated with a conditional read. Zero disables the periodic pass. */
    val revalidateInterval: Duration = Duration.ofSeconds(10),
    /** Lag above which a document is logged at WARN and reported to [SnapshotMetrics.onLagExceeded]. */
    val lagWarn: Duration = Duration.ofSeconds(60),
    /** Lag above which a document is logged at ERROR and reported as stale. */
    val lagEscalate: Duration = Duration.ofMinutes(5),
    /** Tenants whose requests fail when the snapshot they use lags more than [failClosedMaxLag] (RFC 0001 §7.1). */
    val failClosedTenants: Set<String> = emptySet(),
    val failClosedMaxLag: Duration = Duration.ofMinutes(30),
    /** How long [SnapshotSource.load] waits for a cold document before it answers with a failed one. */
    val coldLoadTimeout: Duration = Duration.ofSeconds(2),
    /** The most app documents held at once; the least recently used one is evicted beyond it. */
    val maxApps: Int = 1024,
    val compileThreads: Int = 2,
    /** Compilations waiting for a thread; beyond it a load fails closed (RFC 0003 §6 F8). */
    val compileQueueCapacity: Int = 64,
    /** Documents reloaded at once by a revalidation pass or after a reset. */
    val reloadConcurrency: Int = 4,
    val prewarmInitialBackoff: Duration = Duration.ofSeconds(1),
    val prewarmMaxBackoff: Duration = Duration.ofSeconds(30),
    /** The least time between two lag log lines for the same document. */
    val lagLogInterval: Duration = Duration.ofMinutes(1),
) {
    init {
        require(!revalidateInterval.isNegative) { "revalidateInterval must not be negative." }
        require(lagWarn <= lagEscalate) { "lagWarn must not exceed lagEscalate." }
        require(!failClosedMaxLag.isNegative) { "failClosedMaxLag must not be negative." }
        require((!coldLoadTimeout.isNegative && !coldLoadTimeout.isZero)) { "coldLoadTimeout must be positive." }
        require(maxApps > 0) { "maxApps must be positive." }
        require(compileThreads > 0) { "compileThreads must be positive." }
        require(compileQueueCapacity > 0) { "compileQueueCapacity must be positive." }
        require(reloadConcurrency > 0) { "reloadConcurrency must be positive." }
        require(
            !prewarmInitialBackoff.isNegative && !prewarmInitialBackoff.isZero && prewarmInitialBackoff <= prewarmMaxBackoff
        ) {
            "prewarmInitialBackoff must be positive and must not exceed prewarmMaxBackoff."
        }
    }
}
