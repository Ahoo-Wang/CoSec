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

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.parity.LegacyCases
import me.ahoo.cosec.parity.LegacyData
import me.ahoo.cosec.parity.PolicySetParity
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * P5 (RFC 0003 §8.4) against the real 7.0 read path: [RedisPolicyRepository] and [RedisAppRolePermissionRepository]
 * over real CoCache engines (in-memory backend), holding the same generated data as the snapshot documents. The
 * caches only store what they can decode, so the cases hold no undecodable entry or document; cosec-core's P5 covers
 * those against its model of these repositories.
 *
 * The global policy index is a set, whose order is not defined (RFC 0003 §7 X3): the global groups are compared by id
 * order on both sides.
 */
class SnapshotRepositoryParityTest {
    private fun CoSecTestCaches.store(data: LegacyData) {
        data.policies.forEach { (id, json) -> policyCache.put(id, LegacyData.decodePolicy(json)) }
        if (data.globalIndex.isNotEmpty()) {
            globalPolicyIndexCache.add(GlobalPolicyIndexCache.CACHE_KEY, data.globalIndex)
        }
        data.appPermissions.forEach { (appId, json) ->
            appPermissionCache.put(appId, CoSecJsonSerializer.readValue(json, AppPermission::class.java))
        }
        data.grants.forEach { (appId, grants) ->
            grants.forEach { (key, json) ->
                val role = checkNotNull(RoleScope.parse(key))
                rolePermissionCache.put(
                    AppSpacedRoleId(appId, role.roleId, role.spaceId),
                    LegacyData.decodeGrants(json)
                )
            }
        }
    }

    /** [described] with the global layer's groups in id order. */
    private fun globalInIdOrder(described: List<Any?>): List<Any?> = described.map { layer ->
        layer as List<*>
        if (layer[0] != LayerKind.GLOBAL || layer[1] != "loaded") {
            return@map layer
        }
        val groups = (layer[3] as List<*>).sortedBy { (it as List<*>)[0] as String }
        layer.subList(0, 3) + listOf(groups)
    }

    @Test
    fun snapshotAndRedisRepositoriesBuildTheSamePolicySet() {
        val cases = LegacyCases(SEED, brokenEntries = false, failedDocuments = false)
        var roleGroups = 0
        repeat(ITERATIONS) { iteration ->
            val case = cases.next()
            CoSecTestCaches().use { caches ->
                caches.store(case.data)
                val expected = PolicySetParity.ofRepositories(
                    caches.policyRepository(),
                    caches.appRolePermissionRepository(),
                    case.request,
                    case.context(),
                )
                val actual = PolicySetParity.ofSnapshot(case.data, case.request, case.context())
                globalInIdOrder(
                    actual
                ).assert().describedAs("iteration $iteration").isEqualTo(globalInIdOrder(expected))
                if ((expected[2] as List<*>).let { it[1] == "loaded" && (it[3] as List<*>).isNotEmpty() }) roleGroups++
            }
        }
        roleGroups.assert().isGreaterThan(ITERATIONS / 5)
    }

    companion object {
        private const val SEED = 20261022
        private const val ITERATIONS = 2_000
    }
}
