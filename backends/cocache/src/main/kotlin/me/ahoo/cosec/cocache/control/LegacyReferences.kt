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
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The back-references as stores before 8.0.0-beta.5 kept them, one value per policy ([LegacyReferrersCodec]), at the
 * keys the sets now use: what `cosec-migrate repair-references` reads to convert them. Nothing else reads this.
 */
@InternalCoSecApi
object LegacyReferences {
    /** The cache name of [spec]: not one of CoSec's, created without CoSec's locks, in a runtime of its own. */
    const val CACHE_NAME = "LegacyReferenceIndexCache"

    /** The Redis key pattern of every back-reference under [keyPrefix], for a `SCAN`. */
    fun pattern(keyPrefix: String): String =
        CoSecCacheSpecs.storagePath(keyPrefix, CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME) + ":*"

    /** The target and policy the Redis key [storageKey] under [keyPrefix] names, or null when it names none. */
    fun keyOf(keyPrefix: String, storageKey: String): Pair<SnapshotKey, PolicyId>? {
        val path = CoSecCacheSpecs.storagePath(keyPrefix, CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME) + ":"
        if (!storageKey.startsWith(path)) {
            return null
        }
        val encoded = storageKey.substring(path.length)
        val split = when {
            encoded.startsWith(SnapshotKeyEncoder.PLATFORM + ":") -> SnapshotKeyEncoder.PLATFORM.length
            encoded.startsWith(SnapshotKeyEncoder.APP_PREFIX) ->
                encoded.indexOf(':', SnapshotKeyEncoder.APP_PREFIX.length)
            encoded.startsWith(SnapshotKeyEncoder.TENANT_PREFIX) ->
                encoded.indexOf(':', SnapshotKeyEncoder.TENANT_PREFIX.length)
            else -> -1
        }
        if (split < 0 || split + 1 >= encoded.length) {
            return null
        }
        val target = SnapshotKeyEncoder.decode(encoded.substring(0, split)) ?: return null
        return target to PolicyId(encoded.substring(split + 1))
    }

    /** A `Store` over the keys of the back-references under [keyPrefix], reading the old format; a failed read throws. */
    fun spec(keyPrefix: String): StoreSpec<Pair<SnapshotKey, PolicyId>, Map<SnapshotKey, Long>> {
        val layout = CoSecCacheSpecs.layout(keyPrefix, CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME)
        return StoreSpec.builder<Pair<SnapshotKey, PolicyId>, Map<SnapshotKey, Long>>(CACHE_NAME)
            .keyPrefix(layout.keyPrefix)
            .namespace(layout.namespace)
            .keyEncoder(KeyEncoder { (target, policy) -> ReferenceKeyEncoder.encode(ReferenceKey(target, policy)) })
            .codec(LegacyReferrersCodec)
            .unknownRead(UnknownReadMode.THROW)
            .readOnly()
            .build()
    }
}
