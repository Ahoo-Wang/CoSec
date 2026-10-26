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

import me.ahoo.cocache.api.Codec
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class CoSecCacheSpecsTest {

    private fun <V> Codec<V>.roundTrip(value: V): V = decode(encode(value))

    @Test
    fun policyCodecRoundTrip() {
        val policy = PolicyData(
            id = "2",
            category = "auth",
            name = "auth",
            description = "",
            type = PolicyType.SYSTEM,
            tenantId = "1",
            statements = listOf(StatementData(action = AllActionMatcher.INSTANCE)),
        )
        CoSecCacheSpecs.jacksonCodec<Policy>(CoSecJsonSerializer).roundTrip(policy).assert().isEqualTo(policy)
    }

    @Test
    fun appPermissionCodecRoundTrip() {
        val appPermission = AppPermissionData(
            id = "app",
            groups = listOf(
                PermissionGroupData(
                    name = "group",
                    permissions = listOf(PermissionData(id = "p1", name = "p1", action = AllActionMatcher.INSTANCE))
                )
            ),
        )
        CoSecCacheSpecs.jacksonCodec<AppPermission>(CoSecJsonSerializer).roundTrip(appPermission)
            .assert().isEqualTo(appPermission)
    }

    @Test
    fun scalarCodecsRoundTrip() {
        CoSecCacheSpecs.jacksonCodec<Set<String>>(CoSecJsonSerializer).roundTrip(setOf("a", "b"))
            .assert().isEqualTo(setOf("a", "b"))
        CoSecCacheSpecs.jacksonCodec<Boolean>(CoSecJsonSerializer).roundTrip(true).assert().isTrue()
    }

    @Test
    fun storageLayout() {
        val spec = CoSecCacheSpecs.rolePermission(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer), "tenant-a:")
        spec.namespace.assert().isEqualTo("tenant-a:app:role:permission")
        spec.keyPrefix.assert().isEmpty()
        spec.keyEncoder.encode(AppSpacedRoleId("app", "admin", "space")).assert().isEqualTo("app:admin@space")

        val index = CoSecCacheSpecs.globalPolicyIndex("cosec")
        index.namespace.assert().isEqualTo("cosec:global")
        index.keyEncoder.encode(GlobalPolicyIndexCache.CACHE_KEY)
            .assert().isEqualTo(CoSecCacheSpecs.GLOBAL_POLICY_INDEX_ENCODED_KEY)
    }

    @Test
    fun storageKeys() {
        CoSecTestCaches(keyPrefix = "layout").use { caches ->
            caches.policyCache.put("p1", PolicyData("p1", "c", "n", "", PolicyType.GLOBAL, "t"))
            caches.globalPolicyIndexCache.add(GlobalPolicyIndexCache.CACHE_KEY, listOf("p1"))
            caches.appPermissionCache.put("app", AppPermissionData(id = "app"))
            caches.rolePermissionCache.put(AppSpacedRoleId("app", "admin", "space"), setOf("p"))
            caches.revokedTokenCache.put("jti", true)
            caches.revokedSubjectCache.put("1:t:alice", 1L)
            caches.membershipCache.put("1:t:alice", Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList()))
            mapOf(
                CoSecCacheSpecs.POLICY_CACHE_NAME to ("p1" to "layout:policy:p1"),
                CoSecCacheSpecs.GLOBAL_POLICY_INDEX_CACHE_NAME to ("policy" to "layout:global:policy"),
                CoSecCacheSpecs.APP_PERMISSION_CACHE_NAME to ("app" to "layout:app:permission:app"),
                CoSecCacheSpecs.ROLE_PERMISSION_CACHE_NAME to ("app:admin@space" to "layout:app:role:permission:app:admin@space"),
                CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME to ("jti" to "layout:token:revoked:jti"),
                CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME to ("1:t:alice" to "layout:token:subject:revoked:1:t:alice"),
                CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME to ("1:t:alice" to "layout:membership:1:t:alice"),
            ).forEach { (cacheName, keys) ->
                checkNotNull(caches.runtime.admin(cacheName)).inspect(keys.first).storageKey
                    .assert().isEqualTo(keys.second)
            }
        }
    }
}
