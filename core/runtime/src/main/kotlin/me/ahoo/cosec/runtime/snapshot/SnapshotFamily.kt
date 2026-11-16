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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind

/**
 * One kind of scoped document a [SnapshotSource] holds, by id: app documents by app id, tenant documents by tenant id.
 * Each family keeps at most [maxEntries] documents and evicts the least recently used idle one beyond it; entries for
 * documents the store does not hold are kept apart, at most [maxAbsent] of them (`SNP-7`).
 */
internal class SnapshotFamily<out A : Any>(
    val kind: SnapshotKind,
    val compiler: SnapshotCompiler<A>,
    val maxEntries: Int,
    val maxAbsent: Int = SnapshotOptions.DEFAULT_MAX_ABSENT,
) {
    init {
        require(
            kind == SnapshotKind.APP || kind == SnapshotKind.TENANT
        ) { "A family holds app or tenant documents, not $kind." }
        require(maxEntries > 0) { "maxEntries must be positive." }
        require(maxAbsent > 0) { "maxAbsent must be positive." }
    }

    /** The id [key] names in this family. */
    internal fun idOf(key: SnapshotKey): String = when {
        kind == SnapshotKind.APP && key is SnapshotKey.App -> key.appId
        kind == SnapshotKind.TENANT && key is SnapshotKey.Tenant -> key.tenantId
        else -> throw IllegalArgumentException("[$key] is not a $kind document.")
    }
}
