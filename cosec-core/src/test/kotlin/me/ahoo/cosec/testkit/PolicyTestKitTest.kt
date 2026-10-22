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

import me.ahoo.cosec.api.principal.CoSecPrincipal.Companion.ROOT_ID
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.explain.ExplainPrincipal
import me.ahoo.cosec.explain.ExplainRequest
import me.ahoo.cosec.explain.ExplainerImpl
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.EnumSource

@ParameterizedClass
@EnumSource(ExplainerImpl::class)
class PolicyTestKitTest(private val impl: ExplainerImpl) {
    // The narration goes through the public entry points, exactly as before the trace-based explanation existed.
    private fun run(vararg locations: String): PolicyTestReport = when (impl) {
        ExplainerImpl.NARRATION -> PolicyTestKit.run(*locations)
        ExplainerImpl.TRACE -> PolicyTestKit.run(locations.toList(), RootPrincipalMatcher.NONE, explainFromTrace = true)
    }

    private fun run(
        suite: PolicyTestSuite,
        rootPrincipalMatcher: RootPrincipalMatcher? = null
    ): List<PolicyTestResult> = when {
        impl == ExplainerImpl.TRACE ->
            PolicyTestKit.run(suite, rootPrincipalMatcher ?: RootPrincipalMatcher.NONE, explainFromTrace = true)

        rootPrincipalMatcher == null -> PolicyTestKit.run(suite)
        else -> PolicyTestKit.run(suite, rootPrincipalMatcher)
    }

    @Test
    fun passingSuite() {
        val report = run("classpath:cosec-policy-test/orders-policy-test.json")
        report.results.size.assert().isEqualTo(4)
        report.failures.assert().isEmpty()
        report.assertPasses()
    }

    @Test
    fun roleGrantsAreScopedToTheirAppAndSpace() {
        val report = run("classpath:cosec-policy-test/spaces/*-policy-test.json")
        report.results.size.assert().isEqualTo(5)
        report.assertPasses()
    }

    @Test
    fun malformedRolesAndUnknownAppsFail() {
        assertThrows(IllegalArgumentException::class.java) {
            run(PolicyTestSuite(name = "bad", roles = mapOf("admin@a@b" to setOf("*"))))
        }.message.assert().contains("admin@a@b")
        assertThrows(IllegalArgumentException::class.java) {
            run(
                PolicyTestSuite(
                    appPermissions = listOf("classpath:cosec-policy/test-app-permission.json"),
                    appRoles = mapOf("manage" to mapOf("@team-a" to setOf("*"))),
                ),
            )
        }.message.assert().contains("@team-a")
        assertThrows(IllegalArgumentException::class.java) {
            run(PolicyTestSuite(appRoles = mapOf("unknown" to mapOf("admin" to setOf("*")))))
        }.message.assert().contains("[unknown]")
    }

    @Test
    fun grantsOfUndefinedPermissionsFail() {
        val appPermissions = listOf(
            "classpath:cosec-policy/test-app-permission.json",
            "classpath:cosec-policy-test/spaces/billing-app-permission.json",
        )
        assertThrows(IllegalArgumentException::class.java) {
            run(
                PolicyTestSuite(
                    name = "typo",
                    appPermissions = appPermissions,
                    appRoles = mapOf("manage" to mapOf("shipper@team-a" to setOf("manage.order.typo"))),
                ),
            )
        }.message.assert().contains("[typo]", "[shipper@team-a]", "[manage.order.typo]", "[manage]")
        // A grant in every app must be defined by every app.
        assertThrows(IllegalArgumentException::class.java) {
            run(
                PolicyTestSuite(
                    appPermissions = appPermissions,
                    roles = mapOf("shipper" to setOf("manage.order.ship"))
                ),
            )
        }.message.assert().contains("[manage.order.ship]", "[billing]")
        // `*` still grants every permission of each app.
        val wildcard = PolicyTestSuite(appPermissions = appPermissions, roles = mapOf("admin" to setOf("*", "typo")))
        run(wildcard).assert().isEmpty()
    }

    @Test
    fun failingSuiteExplainsWhatDecided() {
        val report = run("classpath:cosec-policy-test/failing/*.json")
        val failure = report.failures.single()
        failure.case.assert().isEqualTo("expects a deny that does not exist")
        failure.message.assert()
            // The principal lists a policy that does not exist: authorization fails closed instead of letting the
            // global allow win.
            .contains("authorization failed with an error")
            .contains("expected EXPLICIT_DENY, got IMPLICIT_DENY")
            .contains("[gone]")
        val error = assertThrows(AssertionError::class.java) { report.assertPasses() }
        error.message.assert().startsWith("1 of 2 policy test cases failed").contains("wrong › expects a deny")
    }

    @Test
    fun rootIsOffByDefaultAsInTheStarter() {
        val suite = PolicyTestSuite(
            name = "root",
            cases = listOf(
                PolicyTestCase(
                    name = "root id without policies",
                    principal = ExplainPrincipal(id = ROOT_ID, tenantId = Tenant.PLATFORM_TENANT_ID),
                    request = ExplainRequest(path = "/orders"),
                    expect = PolicyTestExpectation(ExpectedResult.IMPLICIT_DENY),
                ),
            ),
        )
        run(suite).single().passed.assert().isTrue()
        run(suite, RootPrincipalMatcher.DEFAULT).single().explanation.authorized.assert().isTrue()
    }

    @Test
    fun missingLocationsFail() {
        assertThrows(IllegalStateException::class.java) { run("classpath:not-exists/*.json") }
        assertThrows(IllegalStateException::class.java) {
            run(PolicyTestSuite(policies = listOf("classpath:not-exists/x.json")))
        }
    }

    @Test
    fun errorsAndMissingRules() {
        val failures = run("classpath:cosec-policy-test/broken/*.json").failures
        failures.single { it.case == "a matcher error" }.message.assert()
            .contains("authorization failed with an error")
            .contains("Matching policy [broken] statement [0] failed")
        failures.single { it.case == "a rule that never decides" }.message.assert()
            .contains("expected role [admin]")
            .contains("expected permission [p]")
            .contains("no rule (nothing matched)")
    }
}
