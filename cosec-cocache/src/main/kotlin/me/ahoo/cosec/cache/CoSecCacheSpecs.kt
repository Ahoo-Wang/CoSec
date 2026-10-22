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

package me.ahoo.cosec.cache

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.codec.jackson.JacksonCodec
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.ForeignFormatMode
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.proxy.CacheProxies
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.PermissionId
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.cache.snapshot.SnapshotDocumentCodec
import me.ahoo.cosec.cache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * CoSec's CoCache declarations.
 *
 * CoCache stores an entry under `{keyPrefix}{namespace}:{key}`; each cache sets an empty `keyPrefix` and a namespace
 * of [cacheNamespace] (`cosec:policy`, `cosec:global`, `cosec:app:permission`, `cosec:app:role:permission`,
 * `cosec:token:revoked`, `cosec:snapshot`). Policies, the global policy index, app permissions and revoked tokens keep the keys of
 * CoSec 6.x, unless the key prefix ends with `:`, which 6.x did not trim; role grants moved to the app-scoped
 * `cosec:app:role:permission` in 7.0. The values are CoCache 6's binary format, so data written by CoSec 6.x
 * (CoCache 5) must be migrated before 7.0 reads it: see [CoSecCoCache5Migration], [RolePermissionKeyMigration] and
 * [CoSecSchemaMarker]. Each namespace also names its own invalidation channel `cocache:inv:{namespace}`, so
 * deployments with different key prefixes stay apart.
 *
 * Policy and permission caches have no loader: Redis is the store of record. [pinnedPolicy] must therefore be the
 * last change to every cache's policy, after any customizer or configuration property.
 */
object CoSecCacheSpecs {
    const val POLICY_CACHE_NAME = "PolicyCache"
    const val GLOBAL_POLICY_INDEX_CACHE_NAME = "GlobalPolicyIndexCache"
    const val APP_PERMISSION_CACHE_NAME = "AppPermissionCache"
    const val ROLE_PERMISSION_CACHE_NAME = "RolePermissionCache"
    const val REVOKED_TOKEN_CACHE_NAME = "RevokedTokenCache"

    /** The snapshot documents of RFC 0003 (§4.1); nothing reads them before RFC 0003 PR-5 wires them. */
    const val POLICY_SNAPSHOT_CACHE_NAME = "PolicySnapshotCache"

    /**
     * How long a local copy of a snapshot document lives (RFC 0003 §4.5, Q7). The copy serves cold loads and writers
     * only: installed snapshots are confirmed against Redis with conditional reads, so this does not bound their lag.
     */
    val DEFAULT_SNAPSHOT_LOCAL_MAX_AGE: Duration = Duration.ofSeconds(30)

    /** Encoded key of the single global policy index entry, whatever key the caller passes. */
    const val GLOBAL_POLICY_INDEX_ENCODED_KEY = "policy"

    /** Names of the caches whose only copy is Redis. */
    val STORE_OF_RECORD_CACHE_NAMES: Set<String> = setOf(
        POLICY_CACHE_NAME,
        GLOBAL_POLICY_INDEX_CACHE_NAME,
        APP_PERMISSION_CACHE_NAME,
        ROLE_PERMISSION_CACHE_NAME,
        POLICY_SNAPSHOT_CACHE_NAME,
    )

    private val NAMESPACES: Map<String, String> = mapOf(
        POLICY_CACHE_NAME to "policy",
        GLOBAL_POLICY_INDEX_CACHE_NAME to "global",
        APP_PERMISSION_CACHE_NAME to "app:permission",
        ROLE_PERMISSION_CACHE_NAME to "app:role:permission",
        REVOKED_TOKEN_CACHE_NAME to "token:revoked",
        POLICY_SNAPSHOT_CACHE_NAME to "snapshot",
    )

    /** The CoCache namespace of [cacheName]: `{keyPrefix}:policy` and so on. */
    fun cacheNamespace(keyPrefix: String, cacheName: String): String =
        "${keyPrefix.trimEnd(':')}:${NAMESPACES.getValue(cacheName)}"

    /**
     * Policy and permission caches are the store of record, so:
     *
     * - entries never expire (`ttl = null`); with an expiry, policies would silently disappear;
     * - content the cache cannot read, such as an unmigrated CoCache 5 value, fails the read
     *   ([ForeignFormatMode.FAIL]) and stays in Redis, instead of being reclaimed: with no loader, a reclaim would
     *   delete the only copy;
     * - a failed Redis read throws ([ReadFailureMode.THROW]) instead of degrading to a miss, which would silently
     *   drop a policy or permission. The security pipeline turns the error into a denied (500) response. (A
     *   `SetCache` such as the global policy index has nothing to degrade to and always rethrows; with CoCache 5 a
     *   degraded index read meant "no global policies", so explicit DENY rules were skipped.)
     */
    fun CachePolicy.storeOfRecord(): CachePolicy = toBuilder()
        .ttl(null)
        .readFailure(ReadFailureMode.THROW)
        .foreignFormat(ForeignFormatMode.FAIL)
        .build()

    /**
     * Revoked tokens are not the store of record (they expire with their tokens, and a failed read degrades on
     * purpose), but a 6.x revocation must not be reclaimed either: deleting it would accept the token again. Content
     * the cache cannot read therefore fails the read ([ForeignFormatMode.FAIL]), whatever `cocache.*` says.
     */
    fun CachePolicy.failOnForeignFormat(): CachePolicy = toBuilder()
        .foreignFormat(ForeignFormatMode.FAIL)
        .build()

    /**
     * What CoSec fixes in the policy of [cacheName], applied after any customizer or property:
     * [storeOfRecord] for policies and permissions, [failOnForeignFormat] for revoked tokens.
     */
    fun pinnedPolicy(cacheName: String, policy: CachePolicy): CachePolicy = when (cacheName) {
        in STORE_OF_RECORD_CACHE_NAMES -> policy.storeOfRecord()
        REVOKED_TOKEN_CACHE_NAME -> policy.failOnForeignFormat()
        else -> policy
    }

    fun policy(
        codec: Codec<Policy>,
        keyPrefix: String = CoSec.COSEC,
        policy: CachePolicy = CachePolicy.DEFAULT
    ): CacheSpec<String, Policy> = CacheSpec.builder<String, Policy>(POLICY_CACHE_NAME)
        .storageLayout(keyPrefix)
        .codec(codec)
        .policy(policy.storeOfRecord())
        .build()

    fun globalPolicyIndex(
        keyPrefix: String = CoSec.COSEC,
        policy: CachePolicy = SetCacheSpec.DEFAULT_POLICY
    ): SetCacheSpec<String, String> = SetCacheSpec.builder<String, String>(GLOBAL_POLICY_INDEX_CACHE_NAME)
        .namespace(cacheNamespace(keyPrefix, GLOBAL_POLICY_INDEX_CACHE_NAME))
        .keyPrefix("")
        .keyEncoder { GLOBAL_POLICY_INDEX_ENCODED_KEY }
        .elementCodec(StringCodec)
        .policy(policy.storeOfRecord())
        .build()

    fun appPermission(
        codec: Codec<AppPermission>,
        keyPrefix: String = CoSec.COSEC,
        policy: CachePolicy = CachePolicy.DEFAULT
    ): CacheSpec<AppId, AppPermission> = CacheSpec.builder<AppId, AppPermission>(APP_PERMISSION_CACHE_NAME)
        .storageLayout(keyPrefix)
        .codec(codec)
        .policy(policy.storeOfRecord())
        .build()

    fun rolePermission(
        codec: Codec<Set<PermissionId>>,
        keyPrefix: String = CoSec.COSEC,
        policy: CachePolicy = CachePolicy.DEFAULT
    ): CacheSpec<AppSpacedRoleId, Set<PermissionId>> =
        CacheSpec.builder<AppSpacedRoleId, Set<PermissionId>>(ROLE_PERMISSION_CACHE_NAME)
            .storageLayout(keyPrefix)
            .codec(codec)
            .policy(policy.storeOfRecord())
            .build()

    /**
     * The snapshot documents (RFC 0003 §4.1): [storeOfRecord] like every policy cache, keys encoded explicitly
     * ([SnapshotKeyEncoder]), values by [SnapshotDocumentCodec]. Create it with
     * [me.ahoo.cosec.cache.snapshot.createPolicySnapshotCache], never through an interface proxy (RFC 0003 §10).
     *
     * Redis must not evict these keys: run it with `maxmemory-policy noeviction`. Under an `allkeys-*` policy a
     * document can vanish. An instance that had read it fails closed (RFC 0003 §4.4 F2a), but an instance that starts
     * afterwards reads it as absent (F2): without the policies document there are no global policies, so a global DENY
     * no longer applies.
     */
    fun policySnapshot(
        keyPrefix: String = CoSec.COSEC,
        policy: CachePolicy = CachePolicy.DEFAULT,
        localMaxAge: Duration = DEFAULT_SNAPSHOT_LOCAL_MAX_AGE,
    ): CacheSpec<SnapshotKey, SnapshotDocument> =
        CacheSpec.builder<SnapshotKey, SnapshotDocument>(POLICY_SNAPSHOT_CACHE_NAME)
            .namespace(cacheNamespace(keyPrefix, POLICY_SNAPSHOT_CACHE_NAME))
            .keyPrefix("")
            .keyEncoder(SnapshotKeyEncoder)
            .codec(SnapshotDocumentCodec)
            .policy(policy.toBuilder().localMaxAge(localMaxAge).build().storeOfRecord())
            .build()

    /**
     * Revocations are always written with an explicit lifetime. The cache keeps CoCache's degraded read mode on
     * purpose: tokens are still signature-verified and expire on their own, and failing closed would reject every
     * authenticated request during a Redis outage. An unreadable revocation still fails ([failOnForeignFormat]).
     */
    fun revokedToken(
        codec: Codec<Boolean>,
        keyPrefix: String = CoSec.COSEC,
        policy: CachePolicy = CachePolicy.DEFAULT
    ): CacheSpec<String, Boolean> = CacheSpec.builder<String, Boolean>(REVOKED_TOKEN_CACHE_NAME)
        .storageLayout(keyPrefix)
        .codec(codec)
        .policy(
            policy.toBuilder()
                .absentTtl(Duration.ofSeconds(RevokedTokenCache.MISSING_TTL_SECONDS))
                .build()
                .failOnForeignFormat()
        )
        .build()

    inline fun <reified V : Any> jacksonCodec(mapper: ObjectMapper): Codec<V> =
        JacksonCodec(mapper, object : TypeReference<V>() {}.type)

    private fun <K : Any, V : Any> CacheSpec.Builder<K, V>.storageLayout(keyPrefix: String) = apply {
        namespace(cacheNamespace(keyPrefix, name))
        keyPrefix("")
        keyEncoder(KeyEncoder.ofToString())
    }
}

/**
 * Creates the cache declared by [spec] and exposes it as [C], one of CoSec's cache interfaces.
 */
inline fun <reified C : Cache<K, V>, K : Any, V : Any> CacheRuntime.createCache(spec: CacheSpec<K, V>): C =
    CacheProxies.create(C::class.java, create(spec))

/**
 * Creates the global policy index declared by [spec].
 */
fun CacheRuntime.createGlobalPolicyIndex(spec: SetCacheSpec<String, String>): GlobalPolicyIndexCache =
    CacheProxies.create(GlobalPolicyIndexCache::class.java, create(spec))
