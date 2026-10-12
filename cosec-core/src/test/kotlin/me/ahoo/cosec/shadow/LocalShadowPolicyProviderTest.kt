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

package me.ahoo.cosec.shadow

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LocalShadowPolicyProviderTest {
    @Test
    fun load() {
        val policies = LocalShadowPolicyProvider(setOf("classpath:cosec-shadow/*-policy.json")).getPolicies()
        policies.single().name.assert().isEqualTo("Orders v2")
    }

    @Test
    fun missingLocationLoadsNothing() {
        LocalShadowPolicyProvider(setOf("classpath:not-exists/*-policy.json")).getPolicies().assert().isEmpty()
    }

    @Test
    fun invalidPolicyFailsFast() {
        assertThrows(IllegalStateException::class.java) {
            LocalShadowPolicyProvider(setOf("classpath:cosec-shadow/invalid/*.json")).getPolicies()
        }
    }

    @Test
    fun duplicateIdsFailFast() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalShadowPolicyProvider(
                setOf("classpath:cosec-shadow/*-policy.json", "classpath*:cosec-shadow/orders-policy.json")
            )
                .getPolicies()
        }
    }
}
