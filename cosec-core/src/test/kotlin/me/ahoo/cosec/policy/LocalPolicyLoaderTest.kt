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

package me.ahoo.cosec.policy

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LocalPolicyLoaderTest {

    @Test
    fun getPolicies() {
        val loader = LocalPolicyLoader(
            setOf(
                "classpath:cosec-policy-not-found",
                "classpath:cosec-policy/*-policy.json",
                "classpath:cosec-policy/test-policy.json"
            )
        )
        loader.policies.map { it.id }.assert().containsExactly("id")
    }

    @Test
    fun invalidPolicyFailsLoadingInsteadOfBeingSkipped() {
        assertThrownBy<IllegalStateException> {
            LocalPolicyLoader(
                setOf("classpath:cosec-policy/test-policy.json", "classpath:cosec-policy-invalid/*.json")
            ).policies
        }.hasMessageContaining("error-policy.json")
    }

    @Test
    fun unknownMatcherTypeFailsLoading(@TempDir dir: File) {
        dir.resolve("unknown-policy.json").writeText(
            """
            {
              "id": "unknown", "name": "unknown", "category": "", "description": "", "type": "global",
              "tenantId": "(platform)",
              "statements": [{ "effect": "deny", "action": "*", "condition": { "noSuchMatcher": { "value": "x" } } }]
            }
            """.trimIndent(),
        )
        assertThrownBy<IllegalStateException> {
            LocalPolicyLoader(setOf("file:${dir.absolutePath}/*.json")).policies
        }.hasMessageContaining("unknown-policy.json")
    }
}
