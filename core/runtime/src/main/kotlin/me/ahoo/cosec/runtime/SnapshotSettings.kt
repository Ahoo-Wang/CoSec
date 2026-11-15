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

package me.ahoo.cosec.runtime

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import java.time.Duration

/**
 * How a pipeline built over a document store keeps its documents (RFC 0003), set in [PipelineBuilder.snapshots] and read
 * once, at build. The properties are the starter's `cosec.authorization.snapshot.*`, with the same defaults.
 */
@ExperimentalCoSecApi
class SnapshotSettings internal constructor() {
    /**
     * How often every installed document is revalidated with a conditional read, at least 1 second and at most 5
     * minutes, less than [lagWarn], and less than [failClosedMaxLag] when [failClosedTenants] are set (`SNP-6`).
     */
    var revalidateInterval: Duration = DEFAULTS.revalidateInterval

    /** Lag above which a document is logged at WARN. */
    var lagWarn: Duration = DEFAULTS.lagWarn

    /** Lag above which a document is logged at ERROR and reported as stale. */
    var lagEscalate: Duration = DEFAULTS.lagEscalate

    /** Tenants whose requests fail when the documents they use lag more than [failClosedMaxLag] (RFC 0001 §7.1). */
    var failClosedTenants: Set<String> = DEFAULTS.failClosedTenants

    var failClosedMaxLag: Duration = DEFAULTS.failClosedMaxLag

    /** How long a request waits for a document not yet installed before it is answered as unavailable (503). */
    var coldLoadTimeout: Duration = DEFAULTS.coldLoadTimeout

    /** The most app documents held at once; the least recently used one is evicted beyond it. */
    var maxApps: Int = DEFAULTS.maxApps

    /** The most tenant documents held at once; the least recently used one is evicted beyond it. */
    var maxTenants: Int = DEFAULTS.maxTenants

    var compileThreads: Int = DEFAULTS.compileThreads

    /** The apps whose documents are loaded at build, after the platform's; [Pipeline.awaitDocuments] waits for them. */
    var prewarmApps: Set<String> = emptySet()

    /** The tenants whose documents are loaded at build, after the platform's; [Pipeline.awaitDocuments] waits for them. */
    var prewarmTenants: Set<String> = emptySet()

    internal fun toOptions(): SnapshotOptions = SnapshotOptions(
        revalidateInterval = revalidateInterval,
        lagWarn = lagWarn,
        lagEscalate = lagEscalate,
        failClosedTenants = failClosedTenants,
        failClosedMaxLag = failClosedMaxLag,
        coldLoadTimeout = coldLoadTimeout,
        maxApps = maxApps,
        maxTenants = maxTenants,
        compileThreads = compileThreads,
    )

    private companion object {
        val DEFAULTS = SnapshotOptions()
    }
}
