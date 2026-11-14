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
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions

/**
 * The source's options. A `revalidate-interval` out of bounds fails startup naming the property (`SNP-6`, RC1-50): the
 * periodic pass is the only bound on a change whose invalidation was lost, so it cannot be turned off.
 */
internal fun RuntimeAuthorizationProperties.Snapshot.toOptions(): SnapshotOptions {
    SnapshotOptions.revalidateIntervalProblem(revalidateInterval, lagWarn, failClosed.tenants, failClosed.maxLag)?.let {
        throw IllegalStateException("$REVALIDATE_INTERVAL_PROPERTY ($revalidateInterval) $it.")
    }
    return options()
}

internal const val REVALIDATE_INTERVAL_PROPERTY = "cosec.authorization.snapshot.revalidate-interval"

private fun RuntimeAuthorizationProperties.Snapshot.options(): SnapshotOptions = SnapshotOptions(
    revalidateInterval = revalidateInterval,
    lagWarn = lagWarn,
    lagEscalate = lagEscalate,
    failClosedTenants = failClosed.tenants,
    failClosedMaxLag = failClosed.maxLag,
    coldLoadTimeout = coldLoadTimeout,
    maxApps = maxApps,
    maxTenants = maxTenants,
    compileThreads = compileThreads,
)
