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

package me.ahoo.cosec.cache.snapshot

import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration

/**
 * The CoCache store of snapshot documents (RFC 0003 §4.1): one Redis key per document,
 * `<keyPrefix>:snapshot:policies`, `<keyPrefix>:snapshot:platform`, `<keyPrefix>:snapshot:app:<appId>` and
 * `<keyPrefix>:snapshot:tenant:<tenantId>`.
 *
 * It is the `Store` [CoCacheFactory.create] returns, not an interface proxy (RFC 0003 §10): native images need no proxy
 * hint for it. Besides the store, it carries what [RedisSnapshotDocumentStore] needs to subscribe to the keyspace's
 * invalidations and to bound the age of a local copy.
 */
class PolicySnapshotCache internal constructor(
    delegate: Store<SnapshotKey, SnapshotDocument>,
    /** The CoCache keyspace, which also names the invalidation channel (`{keyPrefix}inv:{namespace}`). */
    val keyspace: Keyspace,
    /** The oldest a local (L2) copy can be: CoCache's effective `localMaxAge`. */
    val localCopyMaxAge: Duration,
    internal val channel: InvalidationChannel,
) : Store<SnapshotKey, SnapshotDocument> by delegate {
    /** The storage key of [key]. */
    internal fun storageKey(key: SnapshotKey): String = keyspace.storageKey(SnapshotKeyEncoder.encode(key))

    /**
     * The snapshot key an invalidation names, or null for a key no document has. CoCache hands invalidation listeners the
     * encoded key, the part of the storage key after `{keyPrefix}{namespace}:`, which [SnapshotKeyEncoder] wrote.
     */
    internal fun snapshotKeyOf(encodedKey: String): SnapshotKey? = SnapshotKeyEncoder.decode(encodedKey)

    override fun toString(): String = "PolicySnapshotCache(keyspace=$keyspace)"
}

/**
 * Explicit key encoding (RFC 0003 §10, RFC 0004 §7.1): `platform`, `app:<appId>` and `tenant:<tenantId>`.
 * `KeyEncoder.ofToString()` would need a reflection hint, and would tie the Redis layout to [SnapshotKey.toString].
 */
@InternalCoSecApi
object SnapshotKeyEncoder : KeyEncoder<SnapshotKey> {
    const val PLATFORM = "platform"
    const val APP_PREFIX = "app:"
    const val TENANT_PREFIX = "tenant:"

    override fun encode(key: SnapshotKey): String = when (key) {
        SnapshotKey.Platform -> PLATFORM
        is SnapshotKey.App -> APP_PREFIX + key.appId
        is SnapshotKey.Tenant -> TENANT_PREFIX + key.tenantId
    }

    /** The key [encoded] names, or null when it names none, such as an invalid app or tenant id. */
    fun decode(encoded: String): SnapshotKey? = when {
        encoded == PLATFORM -> SnapshotKey.Platform
        encoded.startsWith(APP_PREFIX) -> encoded.substring(APP_PREFIX.length)
            .takeIf { SnapshotKey.App.isValidAppId(it) }
            ?.let { SnapshotKey.App(it) }

        encoded.startsWith(TENANT_PREFIX) -> encoded.substring(TENANT_PREFIX.length)
            .takeIf { SnapshotKey.Tenant.isValidTenantId(it) }
            ?.let { SnapshotKey.Tenant(it) }

        else -> null
    }
}

/**
 * Creates the store declared by [spec], which [CoSecCacheSpecs.policySnapshot] builds, with CoSec's locks
 * ([CoSecCacheSpecs.locks]): the application's `cocache.*` properties and customizers apply to it, except for its storage
 * layout, codec, lifetime, failed reads and local max age.
 */
fun CoCacheFactory.createPolicySnapshotCache(spec: StoreSpec<SnapshotKey, SnapshotDocument>): PolicySnapshotCache {
    require(spec.name == CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME) { "Not the snapshot store: ${spec.name}." }
    val store = create(spec, CoSecCacheSpecs.locks(spec.name))
    val policy = checkNotNull(runtime.policy(spec.name)) { "The snapshot store ${spec.name} was not created." }
    return PolicySnapshotCache(store, spec.keyspace, policy.localMaxAge, runtime.backend.channel)
}
