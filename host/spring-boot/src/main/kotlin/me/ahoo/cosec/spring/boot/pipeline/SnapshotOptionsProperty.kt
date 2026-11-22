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

package me.ahoo.cosec.spring.boot.pipeline

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.HeapBudget
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration

/**
 * The source's options. A `revalidate-interval` out of bounds fails startup naming the property (`SNP-6`, RC1-50): the
 * periodic pass is the only bound on a change whose invalidation was lost, so it cannot be turned off.
 */
internal fun RuntimeAuthorizationProperties.Snapshot.toOptions(pipelineTimeout: Duration): SnapshotOptions {
    val derived = derived(pipelineTimeout)
    SnapshotOptions.revalidateIntervalProblem(
        revalidateInterval,
        derived.lagWarn,
        failClosed.tenants,
        failClosed.maxLag
    )
        ?.let { throw IllegalStateException("$REVALIDATE_INTERVAL_PROPERTY ($revalidateInterval) $it.") }
    return options(derived)
}

/** Lag above which a document is logged at WARN: [SnapshotOptions.LAG_WARN_PASSES] passes of `revalidate-interval`. */
internal fun RuntimeAuthorizationProperties.Snapshot.lagWarn(): Duration =
    revalidateInterval.multipliedBy(SnapshotOptions.LAG_WARN_PASSES.toLong())

/**
 * What the starter no longer asks for, computed from `revalidate-interval`, the pipeline's timeout and the machine
 * ([SnapshotOptions.derive]); the startup log says what it came to ([DerivedSettingsLog]).
 */
internal fun RuntimeAuthorizationProperties.Snapshot.derived(pipelineTimeout: Duration): SnapshotOptions.Derived =
    SnapshotOptions.derive(revalidateInterval, pipelineTimeout)

/**
 * The documents loaded before the instance takes traffic: the platform's, the configured apps, the host's own app
 * (`cosec.authorization.scope.app-id`, `SNP-20`) unless `prewarm-host-apps` is off, then the configured tenants. A
 * gateway adds the apps its routes name once its routes exist ([GatewayRouteApps]).
 */
internal fun RuntimeAuthorizationProperties.prewarmKeys(): List<SnapshotKey> {
    val hostApp = scope.appId?.takeIf { snapshot.prewarmHostApps && SnapshotKey.App.isValidAppId(it) }
    val apps = (snapshot.prewarmApps + listOfNotNull(hostApp)).distinct()
    return listOf(SnapshotKey.Platform) + apps.map { SnapshotKey.App(it) } +
        snapshot.prewarmTenants.map { SnapshotKey.Tenant(it) }
}

internal const val REVALIDATE_INTERVAL_PROPERTY = "cosec.authorization.snapshot.revalidate-interval"

private fun RuntimeAuthorizationProperties.Snapshot.options(derived: SnapshotOptions.Derived): SnapshotOptions =
    SnapshotOptions(
        revalidateInterval = revalidateInterval,
        lagWarn = derived.lagWarn,
        lagEscalate = derived.lagEscalate,
        failClosedTenants = failClosed.tenants,
        failClosedMaxLag = failClosed.maxLag,
        coldLoadTimeout = derived.coldLoadTimeout,
        documentsBudget = HeapBudget.of().documents,
        compileThreads = derived.compileThreads,
        reloadConcurrency = derived.reloadConcurrency,
    )
