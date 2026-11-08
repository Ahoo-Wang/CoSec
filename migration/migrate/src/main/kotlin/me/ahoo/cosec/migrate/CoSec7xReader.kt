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

import io.lettuce.core.api.sync.RedisCommands
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.principal.SpacedRoleId
import me.ahoo.cosec.migrate.v7.api.context.request.AppId
import me.ahoo.cosec.migrate.v7.api.policy.PolicyId
import me.ahoo.cosec.migrate.v7.cache.AppSpacedRoleId
import me.ahoo.cosec.migrate.v7.cache.V7CacheSpecs

/**
 * Reads CoSec 7.x policy data in Redis (RFC 0003 §4.7, RFC 0004 §8.2) as it is stored: each policy, app permission and
 * role grant as its bytes, and the global policy index. It decodes nothing, so data 7.x cannot decode is still read
 * and then rejected with its key by the converter.
 *
 * 7.x stored its data through CoCache 6, whose values CoCache 8 does not read, so this reads CoCache 6's format 1
 * itself ([CoCache6Format]) with plain Redis commands. Content that is not format 1 (unmigrated 5.x data, a newer
 * format, a value of another type) fails the read.
 *
 * The keys come from [keys], which scans Redis; the reader reads through the same storage layout as 7.0's caches.
 */
class CoSec7xReader(
    private val redis: RedisCommands<ByteArray, ByteArray>,
    keyPrefix: String,
) {
    /** The keys to read; [keys] finds them in Redis. */
    data class Keys(
        val policyIds: Set<PolicyId>,
        val appIds: Set<AppId>,
        val grants: Set<AppSpacedRoleId>,
    )

    private val policyNamespace = V7CacheSpecs.cacheNamespace(keyPrefix, V7CacheSpecs.POLICY_CACHE_NAME)
    private val appNamespace = V7CacheSpecs.cacheNamespace(keyPrefix, V7CacheSpecs.APP_PERMISSION_CACHE_NAME)
    private val grantNamespace = V7CacheSpecs.cacheNamespace(keyPrefix, V7CacheSpecs.ROLE_PERMISSION_CACHE_NAME)
    private val globalIndexKey =
        "${V7CacheSpecs.cacheNamespace(keyPrefix, V7CacheSpecs.GLOBAL_POLICY_INDEX_CACHE_NAME)}:" +
            V7CacheSpecs.GLOBAL_POLICY_INDEX_ENCODED_KEY

    /** The stored bytes under [storageKey], or null when there is no value. */
    private fun scalar(storageKey: String): ByteArray? =
        CoCache6Format.scalar(storageKey, redis.type(storageKey.encodeToByteArray())) {
            redis.get(storageKey.encodeToByteArray())
        }

    /** The ids in the global policy index. */
    fun globalIndex(): Set<PolicyId> {
        val key = globalIndexKey.encodeToByteArray()
        return CoCache6Format.members(globalIndexKey, redis.type(key)) {
            redis.hkeys(key) to redis.hget(key, CoCache6Format.SET_FORMAT_FIELD)
        }.mapTo(LinkedHashSet()) { it.decodeToString() }
    }

    /** The stored bytes of policy [id], or null when it has none. */
    fun policy(id: PolicyId): ByteArray? = scalar("$policyNamespace:$id")

    /** The stored bytes of the app permission of [appId], or null when it has none. */
    fun appPermission(appId: AppId): ByteArray? = scalar("$appNamespace:$appId")

    /** The stored bytes of the grants of [grantId], or null when it has none. */
    fun grants(grantId: AppSpacedRoleId): ByteArray? = scalar("$grantNamespace:$grantId")

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
