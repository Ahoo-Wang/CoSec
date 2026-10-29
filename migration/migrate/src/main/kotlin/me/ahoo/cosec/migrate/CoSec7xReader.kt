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

package me.ahoo.cosec.migrate

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.principal.SpacedRoleId
import me.ahoo.cosec.cache.CoSecCacheSpecs.storeOfRecord
import me.ahoo.cosec.migrate.v7.api.context.request.AppId
import me.ahoo.cosec.migrate.v7.api.policy.PolicyId
import me.ahoo.cosec.migrate.v7.cache.AppSpacedRoleId
import me.ahoo.cosec.migrate.v7.cache.GlobalPolicyIndexCache
import me.ahoo.cosec.migrate.v7.cache.V7CacheSpecs
import me.ahoo.cosec.migrate.v7.cache.createGlobalPolicyIndex

/**
 * Reads CoSec 7.x policy data in Redis (RFC 0003 §4.7, RFC 0004 §8.2) as it is stored: each policy, app permission and
 * role grant as its bytes, and the global policy index. It decodes nothing, so data 7.x cannot decode is still read
 * and then rejected with its key by the converter. Content that CoCache 6 cannot read at all (unmigrated 5.x data, a
 * newer format) fails the read.
 *
 * The keys come from [keys], which scans Redis; the reader reads through the same storage layout as 7.0's caches.
 */
class CoSec7xReader(
    backend: CoherenceBackend,
    keyPrefix: String,
) : AutoCloseable {
    /** The keys to read; [keys] finds them in Redis. */
    data class Keys(
        val policyIds: Set<PolicyId>,
        val appIds: Set<AppId>,
        val grants: Set<AppSpacedRoleId>,
    )

    private val runtime = CacheRuntime.builder().backend(backend).build()
    private val globalPolicyIndexCache: GlobalPolicyIndexCache =
        runtime.createGlobalPolicyIndex(V7CacheSpecs.globalPolicyIndex(keyPrefix))
    private val rawPolicies = rawCache(keyPrefix, V7CacheSpecs.POLICY_CACHE_NAME)
    private val rawAppPermissions = rawCache(keyPrefix, V7CacheSpecs.APP_PERMISSION_CACHE_NAME)
    private val rawGrants = rawCache(keyPrefix, V7CacheSpecs.ROLE_PERMISSION_CACHE_NAME)
    private val policyNamespace = V7CacheSpecs.cacheNamespace(keyPrefix, V7CacheSpecs.POLICY_CACHE_NAME)
    private val appNamespace = V7CacheSpecs.cacheNamespace(keyPrefix, V7CacheSpecs.APP_PERMISSION_CACHE_NAME)
    private val grantNamespace = V7CacheSpecs.cacheNamespace(keyPrefix, V7CacheSpecs.ROLE_PERMISSION_CACHE_NAME)
    private val globalIndexKey =
        "${V7CacheSpecs.cacheNamespace(keyPrefix, V7CacheSpecs.GLOBAL_POLICY_INDEX_CACHE_NAME)}:" +
            GlobalPolicyIndexCache.CACHE_KEY

    /** The same storage layout as 7.0's caches, with values as stored bytes. */
    private fun rawCache(keyPrefix: String, cacheName: String): Cache<String, ByteArray> = runtime.create(
        CacheSpec.builder<String, ByteArray>("Raw$cacheName")
            .namespace(V7CacheSpecs.cacheNamespace(keyPrefix, cacheName))
            .keyPrefix("")
            .keyEncoder { it }
            .codec(RawCodec)
            .policy(CachePolicy.DEFAULT.storeOfRecord())
            .build()
    )

    private object RawCodec : Codec<ByteArray> {
        override fun encode(value: ByteArray): ByteArray = value

        override fun decode(bytes: ByteArray): ByteArray = bytes
    }

    /** The ids in the global policy index. */
    fun globalIndex(): Set<PolicyId> = globalPolicyIndexCache[GlobalPolicyIndexCache.CACHE_KEY].orEmpty()

    /** The stored bytes of policy [id], or null when it has none. */
    fun policy(id: PolicyId): ByteArray? = rawPolicies[id]

    /** The stored bytes of the app permission of [appId], or null when it has none. */
    fun appPermission(appId: AppId): ByteArray? = rawAppPermissions[appId]

    /** The stored bytes of the grants of [grantId], or null when it has none. */
    fun grants(grantId: AppSpacedRoleId): ByteArray? = rawGrants[grantId.toString()]

    /**
     * Everything under [keys], with [schemaMarker] as read from `{p}:meta:schema`. Grants of an app without an app
     * permission are kept: the converter decides what they mean.
     */
    fun source(keys: Keys, schemaMarker: Source7x.Marker?): Source7x {
        val input = HashMap<String, ByteArray>()
        val index = globalIndex()
        input[globalIndexKey] = index.sorted().joinToString("\n").encodeToByteArray()
        val policies = keys.policyIds.sorted().mapNotNull { id ->
            val location = "$policyNamespace:$id"
            policy(id)?.let { bytes ->
                input[location] = bytes
                Source7x.Entry(id, location, bytes)
            }
        }
        val grantsByApp = keys.grants.groupBy { it.appId }
        val apps = (keys.appIds + grantsByApp.keys).sorted().map { appId ->
            val location = "$appNamespace:$appId"
            val appPermission = appPermission(appId)?.let { bytes ->
                input[location] = bytes
                Source7x.Entry(appId, location, bytes)
            }
            val grants = grantsByApp[appId].orEmpty().sortedBy { it.toString() }.mapNotNull { grantId ->
                val grantLocation = "$grantNamespace:$grantId"
                grants(grantId)?.let { bytes ->
                    input[grantLocation] = bytes
                    Source7x.Grant(grantId.roleId, grantId.spaceId, grantLocation, bytes)
                }
            }
            Source7x.App(appId, location, appPermission, grants)
        }
        return Source7x(policies, index, apps, schemaMarker, globalIndexKey, Source7x.sha256(input))
    }

    override fun close() {
        runtime.close()
    }

    companion object {
        /**
         * The keys of 7.x data under [keyPrefix], found by [scan] (a SCAN with a MATCH pattern, all keys it returns).
         * The policy history (`<p>:policy:history:*`) is skipped, as `CoSecSchemaMarker` does.
         */
        fun keys(keyPrefix: String, scan: (String) -> Sequence<String>): Keys {
            val policyPrefix = V7CacheSpecs.cacheNamespace(keyPrefix, V7CacheSpecs.POLICY_CACHE_NAME) + ":"
            val appPrefix = V7CacheSpecs.cacheNamespace(keyPrefix, V7CacheSpecs.APP_PERMISSION_CACHE_NAME) + ":"
            val grantPrefix = V7CacheSpecs.cacheNamespace(keyPrefix, V7CacheSpecs.ROLE_PERMISSION_CACHE_NAME) + ":"
            val policyIds = scan("$policyPrefix*").map { it.removePrefix(policyPrefix) }
                .filterNot { it.startsWith(V7CacheSpecs.RESERVED_POLICY_ID_PREFIX) }
                .toSortedSet()
            val appIds = scan("$appPrefix*").map { it.removePrefix(appPrefix) }.toSortedSet()
            val grants = scan("$grantPrefix*").map { it.removePrefix(grantPrefix) }.map(::parseGrantKey).toSet()
            return Keys(policyIds, appIds, grants)
        }

        /** `<appId>:<roleId>[@<spaceId>]`, as [AppSpacedRoleId.toString] writes it. */
        fun parseGrantKey(key: String): AppSpacedRoleId {
            val appSeparator = key.indexOf(AppSpacedRoleId.APP_ID_SEPARATOR)
            require(appSeparator > 0) { "Grant key [$key] has no app id." }
            val role = key.substring(appSeparator + 1)
            val spaceSeparator = role.indexOf(SpacedRoleId.SPACE_ID_SEPARATOR)
            return if (spaceSeparator < 0) {
                AppSpacedRoleId(key.substring(0, appSeparator), role)
            } else {
                AppSpacedRoleId(
                    key.substring(0, appSeparator),
                    role.substring(0, spaceSeparator),
                    role.substring(spaceSeparator + 1)
                )
            }
        }

        /** The grant key of [grantId] in an app: `role`, or `role@space` outside the default space. */
        fun grantKeyOf(grantId: AppSpacedRoleId): String =
            if (grantId.spaceId == SpaceIdCapable.DEFAULT) {
                grantId.roleId
            } else {
                "${grantId.roleId}${SpacedRoleId.SPACE_ID_SEPARATOR}${grantId.spaceId}"
            }
    }
}
