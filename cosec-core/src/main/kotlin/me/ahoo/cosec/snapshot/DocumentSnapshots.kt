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

import java.time.Clock

/**
 * The 8.0 snapshot source (RFC 0004 §7.4): the platform document, shared and never evicted, and the app and tenant
 * families, each bounded by [SnapshotOptions.maxApps] and [SnapshotOptions.maxTenants]. All three compile with
 * [compiler], whose result the engine composes into one view per evaluation.
 */
object DocumentSnapshots {
    fun <T : Any> source(
        store: SnapshotDocumentStore,
        compiler: SnapshotCompiler<T>,
        options: SnapshotOptions = SnapshotOptions(),
        clock: Clock = Clock.systemUTC(),
        metrics: SnapshotMetrics = SnapshotMetrics.NOOP,
    ): SnapshotSource<T, T> =
        SnapshotSource(
            store,
            SnapshotKey.Platform,
            compiler,
            families(compiler, options),
            options,
            clock,
            metrics,
            null,
            null,
            System::nanoTime,
        )

    internal fun <T : Any> families(compiler: SnapshotCompiler<T>, options: SnapshotOptions): List<SnapshotFamily<T>> =
        listOf(
            SnapshotFamily(SnapshotKind.APP, compiler, options.maxApps),
            SnapshotFamily(SnapshotKind.TENANT, compiler, options.maxTenants),
        )
}
