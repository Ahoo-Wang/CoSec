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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration

/**
 * The CoCache cache of snapshot documents (RFC 0003 §4.1): one Redis key per document,
 * `<keyPrefix>:snapshot:policies`, `<keyPrefix>:snapshot:platform`, `<keyPrefix>:snapshot:app:<appId>` and
 * `<keyPrefix>:snapshot:tenant:<tenantId>`.
 *
 * It is the engine [CacheRuntime.create] returns, not an interface proxy (RFC 0003 §10): native images need no proxy
 * hint for it. Besides the cache, it carries what [RedisSnapshotDocumentStore] needs to subscribe to the namespace's
 * invalidations and to bound the age of a local copy.
 */
class PolicySnapshotCache internal constructor(
    delegate: Cache<SnapshotKey, SnapshotDocument>,
    /** The CoCache namespace, which also names the invalidation channel. */
    val namespace: String,
    /** The oldest a local (L2) copy can be: CoCache's `localMaxAge`, or its degraded counterpart if longer. */
    val localCopyMaxAge: Duration,
    internal val channel: InvalidationChannel,
) : Cache<SnapshotKey, SnapshotDocument> by delegate {
    /** The storage key of [key], as CoCache's invalidations name it. */
    internal fun storageKey(key: SnapshotKey): String = "$namespace:${SnapshotKeyEncoder.encode(key)}"

    /** The snapshot key a storage key of this namespace names, or null for any other key. */
    internal fun snapshotKeyOf(storageKey: String): SnapshotKey? {
        val prefix = "$namespace:"
        if (!storageKey.startsWith(prefix)) {
            return null
        }
        return SnapshotKeyEncoder.decode(storageKey.substring(prefix.length))
    }

    override fun toString(): String = "PolicySnapshotCache(namespace='$namespace')"
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

/** Creates the cache declared by [spec], which [me.ahoo.cosec.cache.CoSecCacheSpecs.policySnapshot] builds. */
fun CacheRuntime.createPolicySnapshotCache(spec: CacheSpec<SnapshotKey, SnapshotDocument>): PolicySnapshotCache {
    require(spec.keyPrefix.isEmpty()) { "The snapshot cache keeps its keys under its namespace only." }
    return PolicySnapshotCache(
        create(spec),
        spec.namespace,
        maxOf(spec.policy.localMaxAge, spec.policy.degradedLocalMaxAge),
        backend.channel,
    )
}
