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

package me.ahoo.cosec.testkit

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PolicyTestKitTest {
    @Test
    fun passingSuite() {
        val report = PolicyTestKit.run("classpath:cosec-policy-test/orders-policy-test.json")
        report.results.size.assert().isEqualTo(4)
        report.failures.assert().isEmpty()
        report.assertPasses()
    }

    @Test
    fun rejectedTemplateValuesNeverGrant() {
        val report = PolicyTestKit.run("classpath:cosec-policy-test/template-values-policy-test.json")
        report.results.size.assert().isEqualTo(4)
        report.assertPasses()
    }

    @Test
    fun failingSuiteExplainsWhatDecided() {
        val report = PolicyTestKit.run("classpath:cosec-policy-test/failing/*.json")
        val failure = report.failures.single()
        failure.case.assert().isEqualTo("expects a deny that does not exist")
        failure.message.assert()
            .contains("expected EXPLICIT_DENY, got ALLOW")
            .contains("GLOBAL_POLICY policy [orders] statement [0] 'read-orders'")
            .contains("[gone]")
        val error = assertThrows(AssertionError::class.java) { report.assertPasses() }
        error.message.assert().startsWith("1 of 2 policy test cases failed").contains("wrong › expects a deny")
    }

    @Test
    fun missingLocationsFail() {
        assertThrows(IllegalArgumentException::class.java) { PolicyTestKit.run("classpath:not-exists/*.json") }
        assertThrows(IllegalArgumentException::class.java) {
            PolicyTestKit.run(PolicyTestSuite(policies = listOf("classpath:not-exists/x.json")))
        }
    }

    @Test
    fun errorsAndMissingRules() {
        val failures = PolicyTestKit.run("classpath:cosec-policy-test/broken/*.json").failures
        failures.single { it.case == "a matcher error" }.message.assert()
            .contains("authorization failed with an error")
            .contains("Matching policy [broken] statement [0] failed")
        failures.single { it.case == "a rule that never decides" }.message.assert()
            .contains("expected role [admin]")
            .contains("expected permission [p]")
            .contains("no rule (nothing matched)")
    }
}
