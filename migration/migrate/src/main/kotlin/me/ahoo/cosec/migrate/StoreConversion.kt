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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.control.CoCacheControlStore
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * What `repair-references` converts from the format before 8.0.0-beta.5 (`MIG-4`): the back-reference values, one per
 * policy, into sets, and the document list into a set, whose old key it removes last.
 */
interface StoreConversion {
    /**
     * Converts the back-references of [target]'s [policy] if they are in the old format; whether it did. Each entry says
     * `#own` when [definesItself] says so of its referrer.
     */
    suspend fun convert(target: SnapshotKey, policy: PolicyId, definesItself: suspend (SnapshotKey) -> Boolean): Boolean

    /** The old document list, or null when there is none: the store is in the new format. */
    suspend fun legacyDocuments(): Set<String>?

    /** Adds [entries] to the document set. */
    suspend fun listDocuments(entries: Set<String>)

    /** Removes the old document list: the last write of a repair that converted everything. */
    suspend fun removeLegacyDocuments()

    /** The conversion of a CoCache store. */
    class OfCoCache internal constructor(
        private val store: CoCacheControlStore,
        private val conversion: LegacyConversion,
    ) : StoreConversion {
        override suspend fun convert(
            target: SnapshotKey,
            policy: PolicyId,
            definesItself: suspend (SnapshotKey) -> Boolean,
        ): Boolean = conversion.convert(target, policy, definesItself)

        override suspend fun legacyDocuments(): Set<String>? = store.legacyDocuments()

        override suspend fun listDocuments(entries: Set<String>) = store.listDocuments(entries)

        override suspend fun removeLegacyDocuments() = store.removeLegacyDocuments()
    }
}
