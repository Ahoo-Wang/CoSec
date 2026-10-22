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

package me.ahoo.cosec.authorization.engine

import me.ahoo.cosec.parity.LegacyCases
import me.ahoo.cosec.parity.PolicySetParity
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * P5 (RFC 0003 §8.4): for any generated 7.x data and request, [SnapshotPolicySetLoader] over the compiled snapshot
 * and [PolicySetLoader] over repositories holding the same data build structurally equal policy sets (see
 * [PolicySetParity]). The repositories here are the model in [me.ahoo.cosec.parity.LegacyData], which also holds
 * entries and documents that cannot be decoded; cosec-cocache runs the same generator against the real Redis
 * repositories.
 */
class SnapshotPolicySetPropertyTest {
    @Test
    fun snapshotAndRepositoryBuildTheSamePolicySet() {
        val cases = LegacyCases(SEED)
        var unavailable = 0
        var roleGroups = 0
        repeat(ITERATIONS) { iteration ->
            val case = cases.next()
            val expected = PolicySetParity.ofRepositories(
                case.data.policyRepository,
                case.data.appRolePermissionRepository,
                case.request,
                case.context(),
            )
            val actual = PolicySetParity.ofSnapshot(case.data, case.request, case.context())
            actual.assert().describedAs("iteration $iteration").isEqualTo(expected)
            if (expected.any { (it as List<*>)[1] == "unavailable" }) unavailable++
            if ((expected[2] as List<*>).let { it[1] == "loaded" && (it[3] as List<*>).isNotEmpty() }) roleGroups++
        }
        // The comparison means something only if failures and role groups both occur often.
        unavailable.assert().isBetween(ITERATIONS / 20, ITERATIONS / 2)
        roleGroups.assert().isGreaterThan(ITERATIONS / 5)
    }

    companion object {
        private const val SEED = 20261021
        private const val ITERATIONS = 2_000
    }
}
