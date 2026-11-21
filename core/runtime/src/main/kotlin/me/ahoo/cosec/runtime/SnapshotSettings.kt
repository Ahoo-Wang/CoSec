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
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration

/**
 * How a pipeline built over a document store keeps its documents (RFC 0003), set in [PipelineBuilder.snapshots] and read
 * once, at build. The properties are the starter's `cosec.authorization.snapshot.*`, with the same defaults. What
 * can be computed is not set here: the lag thresholds follow [revalidateInterval], the cold-load timeout the pipeline's
 * timeout, the compile threads the cores, and the reload concurrency is a constant ([SnapshotOptions.derive]).
 */
@ExperimentalCoSecApi
class SnapshotSettings internal constructor(private val pipelineTimeout: () -> Duration) {
    /**
     * How often every installed document is revalidated with a conditional read, at least 1 second and at most 5
     * minutes, and less than [failClosedMaxLag] when [failClosedTenants] are set (`SNP-6`). A document unconfirmed for
     * 6 of these is logged at WARN, for 30 at ERROR as stale.
     */
    var revalidateInterval: Duration = DEFAULTS.revalidateInterval

    /**
     * Tenants whose requests fail when the documents they use lag more than [failClosedMaxLag] (RFC 0001 §7.1); `*`
     * names every tenant. Empty by default: an installed document keeps deciding however long Redis cannot confirm it.
     */
    var failClosedTenants: Set<String> = DEFAULTS.failClosedTenants

    var failClosedMaxLag: Duration = DEFAULTS.failClosedMaxLag

    /** The most app documents held at once; the least recently used one is evicted beyond it. */
    var maxApps: Int = DEFAULTS.maxApps

    /** The most tenant documents held at once; the least recently used one is evicted beyond it. */
    var maxTenants: Int = DEFAULTS.maxTenants

    /**
     * The most app ids held as having no document, apart from [maxApps] (`SNP-7`): app ids come from requests, so this
     * bounds what a client naming made-up apps can make an instance remember.
     */
    var maxAbsentApps: Int = DEFAULTS.maxAbsentApps

    /** The most tenant ids held as having no document, apart from [maxTenants] (`SNP-7`). */
    var maxAbsentTenants: Int = DEFAULTS.maxAbsentTenants

    /** The apps whose documents are loaded at build, after the platform's; [Pipeline.awaitDocuments] waits for them. */
    var prewarmApps: Set<String> = emptySet()

    /** The tenants whose documents are loaded at build, after the platform's; [Pipeline.awaitDocuments] waits for them. */
    var prewarmTenants: Set<String> = emptySet()

    /** The documents to prewarm, besides the platform's: an id that cannot name one is refused here. */
    internal fun prewarmKeys(): List<SnapshotKey> =
        prewarmApps.map { SnapshotKey.App(it) } + prewarmTenants.map { SnapshotKey.Tenant(it) }

    internal fun toOptions(): SnapshotOptions {
        val derived = SnapshotOptions.derive(revalidateInterval, pipelineTimeout())
        return SnapshotOptions(
            revalidateInterval = revalidateInterval,
            lagWarn = derived.lagWarn,
            lagEscalate = derived.lagEscalate,
            failClosedTenants = failClosedTenants,
            failClosedMaxLag = failClosedMaxLag,
            coldLoadTimeout = derived.coldLoadTimeout,
            maxApps = maxApps,
            maxTenants = maxTenants,
            maxAbsentApps = maxAbsentApps,
            maxAbsentTenants = maxAbsentTenants,
            compileThreads = derived.compileThreads,
            reloadConcurrency = derived.reloadConcurrency,
        )
    }

    private companion object {
        val DEFAULTS = SnapshotOptions()
    }
}
