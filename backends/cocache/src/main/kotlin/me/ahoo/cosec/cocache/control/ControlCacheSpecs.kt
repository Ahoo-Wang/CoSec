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

package me.ahoo.cosec.cocache.control

import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.CoSecCacheSpecs.storeOfRecord
import me.ahoo.cosec.cocache.DEFAULT_KEY_PREFIX
import me.ahoo.cosec.cocache.snapshot.SnapshotDocumentCodec
import me.ahoo.cosec.cocache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.StageSlot
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant

/**
 * The caches of `CoCacheControlStore` (RFC 0007 §5.2), next to the snapshot heads of [CoSecCacheSpecs.policySnapshot]:
 *
 * | Cache | Redis key |
 * |---|---|
 * | revision records | `<p>:control:revision:<docKey>:SHA-256:<hex>` |
 * | revision index | `<p>:control:revisions:<docKey>` |
 * | back-references | `<p>:control:refs:<docKey>:<policyId>` |
 * | leases | `<p>:control:lease:<name>` |
 * | lists | `<p>:control:list:documents`, `<p>:control:list:open-changes` |
 * | app list | `<p>:control:apps:all` |
 * | change requests | `<p>:control:change:<changeId>` |
 * | staging slots | `<p>:control:stage:<docKey>` |
 *
 * Digests in keys and values are [SnapshotDigest]'s text form, `SHA-256:<hex>`.
 *
 * All of them are [storeOfRecord] `Store`s, locked by [CoSecCacheSpecs.locks] (`ttl` forever, failed reads throw):
 * records and indexes are not data-plane policy data, but reading a failure as "absent" would let the control plane
 * publish on a wrong base (RFC 0007 §5.2). Leases expire through the lifetime of each write instead. Keys and values are
 * encoded explicitly, without reflection.
 */
internal object ControlCacheSpecs {
    fun revisionRecord(keyPrefix: String = DEFAULT_KEY_PREFIX): StoreSpec<RevisionRecordKey, SnapshotDocument> =
        storeOfRecord(
            CoSecCacheSpecs.REVISION_RECORD_CACHE_NAME,
            keyPrefix,
            RevisionRecordKeyEncoder,
            SnapshotDocumentCodec,
        ).build()

    fun revisionIndex(keyPrefix: String = DEFAULT_KEY_PREFIX): StoreSpec<SnapshotKey, RevisionIndex> =
        storeOfRecord(
            CoSecCacheSpecs.REVISION_INDEX_CACHE_NAME,
            keyPrefix,
            SnapshotKeyEncoder,
            RevisionIndexCodec
        ).build()

    fun referenceIndex(keyPrefix: String = DEFAULT_KEY_PREFIX): StoreSpec<ReferenceKey, Map<SnapshotKey, Long>> =
        storeOfRecord(
            CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME,
            keyPrefix,
            ReferenceKeyEncoder,
            ReferrersCodec,
        ).build()

    fun changeRequest(keyPrefix: String = DEFAULT_KEY_PREFIX): StoreSpec<ChangeId, ChangeRequest> =
        storeOfRecord(
            CoSecCacheSpecs.CHANGE_REQUEST_CACHE_NAME,
            keyPrefix,
            KeyEncoder<ChangeId> { it.value },
            ChangeRequestCacheCodec,
        ).build()

    fun stage(keyPrefix: String = DEFAULT_KEY_PREFIX): StoreSpec<SnapshotKey, StageSlot> =
        storeOfRecord(CoSecCacheSpecs.STAGE_CACHE_NAME, keyPrefix, SnapshotKeyEncoder, StageSlotCodec).build()

    /** What [CoCacheControlStore.documents] and [CoCacheControlStore.openChanges] read: each entry with when it was listed. */
    fun list(keyPrefix: String = DEFAULT_KEY_PREFIX): StoreSpec<String, Map<String, Instant>> =
        storeOfRecord(
            CoSecCacheSpecs.CONTROL_LIST_CACHE_NAME,
            keyPrefix,
            KeyEncoder<String> { it },
            ListedCodec
        ).build()

    /** What [CoCacheControlStore.apps] reads: one value, under the key [CoCacheControlStore]'s `APP_LIST`. */
    fun appList(keyPrefix: String = DEFAULT_KEY_PREFIX): StoreSpec<String, AppList> =
        storeOfRecord(
            CoSecCacheSpecs.CONTROL_APP_LIST_CACHE_NAME,
            keyPrefix,
            KeyEncoder<String> { it },
            AppListCodec
        ).build()

    fun lease(keyPrefix: String = DEFAULT_KEY_PREFIX): StoreSpec<String, String> =
        storeOfRecord(
            CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME,
            keyPrefix,
            KeyEncoder<String> { it },
            StringCodec
        ).build()
}
