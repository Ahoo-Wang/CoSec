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

package me.ahoo.cosec.migrate.v7.cache

import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.proxy.CacheProxies
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.cache.CoSecCacheSpecs.storeOfRecord

/** The names and Redis layout of 7.0's policy and permission caches, which `CoSec7xReader` reads. */
internal object V7CacheSpecs {
    const val POLICY_CACHE_NAME = "PolicyCache"
    const val GLOBAL_POLICY_INDEX_CACHE_NAME = "GlobalPolicyIndexCache"
    const val APP_PERMISSION_CACHE_NAME = "AppPermissionCache"
    const val ROLE_PERMISSION_CACHE_NAME = "RolePermissionCache"

    /** Encoded key of the single global policy index entry, whatever key the caller passes. */
    const val GLOBAL_POLICY_INDEX_ENCODED_KEY = "policy"

    /** Policy ids under this prefix hold 7.x's policy history, not policies. */
    const val RESERVED_POLICY_ID_PREFIX = "history:"

    private val NAMESPACES: Map<String, String> = mapOf(
        POLICY_CACHE_NAME to "policy",
        GLOBAL_POLICY_INDEX_CACHE_NAME to "global",
        APP_PERMISSION_CACHE_NAME to "app:permission",
        ROLE_PERMISSION_CACHE_NAME to "app:role:permission",
    )

    /** The CoCache namespace of [cacheName]: `{keyPrefix}:policy` and so on. */
    fun cacheNamespace(keyPrefix: String, cacheName: String): String =
        "${keyPrefix.trimEnd(':')}:${NAMESPACES.getValue(cacheName)}"

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
}

/** Creates the global policy index declared by [spec]. */
internal fun CacheRuntime.createGlobalPolicyIndex(spec: SetCacheSpec<String, String>): GlobalPolicyIndexCache =
    CacheProxies.create(GlobalPolicyIndexCache::class.java, create(spec))
