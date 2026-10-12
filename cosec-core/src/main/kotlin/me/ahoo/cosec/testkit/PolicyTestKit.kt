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

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.explain.AuthorizationExplainer
import me.ahoo.cosec.explain.DecisionRef
import me.ahoo.cosec.explain.ExplainOptions
import me.ahoo.cosec.explain.ExplainPrincipal
import me.ahoo.cosec.explain.ExplainRequest
import me.ahoo.cosec.explain.ExplainWarningCode
import me.ahoo.cosec.explain.Explanation
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import reactor.core.publisher.Mono
import java.io.FileNotFoundException

enum class ExpectedResult {
    ALLOW,
    EXPLICIT_DENY,
    IMPLICIT_DENY,
}

/**
 * What a case expects. The rule fields are optional; set them to also pin which rule decides.
 */
data class PolicyTestExpectation(
    val result: ExpectedResult,
    val policyId: String? = null,
    val statementName: String? = null,
    val roleId: String? = null,
    val permissionId: String? = null
)

data class PolicyTestCase(
    val name: String,
    val principal: ExplainPrincipal,
    val request: ExplainRequest,
    val expect: PolicyTestExpectation
)

/**
 * A set of cases and the policies they run against.
 *
 * @param policies resource patterns of policy files, e.g. `classpath:cosec-policy/` followed by a file pattern
 * @param appPermissions resource patterns of app permission files
 * @param roles role grants applied to every app permission: role id to permission ids (`*` grants all)
 */
data class PolicyTestSuite(
    val name: String = "",
    val policies: List<String> = emptyList(),
    val appPermissions: List<String> = emptyList(),
    val roles: Map<String, Set<String>> = emptyMap(),
    val cases: List<PolicyTestCase> = emptyList()
)

data class PolicyTestResult(
    val suite: String,
    val case: String,
    val passed: Boolean,
    val message: String,
    val explanation: Explanation
)

class PolicyTestReport(val results: List<PolicyTestResult>) {
    val failures: List<PolicyTestResult>
        get() = results.filterNot { it.passed }

    /**
     * Throws an [AssertionError] listing every failed case, with what decided instead.
     */
    fun assertPasses() {
        val failures = failures
        if (failures.isEmpty()) {
            return
        }
        val details = failures.joinToString(separator = "\n") { "- ${it.suite} › ${it.case}: ${it.message}" }
        throw AssertionError("${failures.size} of ${results.size} policy test cases failed:\n$details")
    }
}

/**
 * Runs declarative policy tests: each case names a principal, a request and the expected decision, and runs
 * through [AuthorizationExplainer] against in-memory repositories, so a failure says which rule decided instead.
 * Framework-agnostic: call [PolicyTestReport.assertPasses] from any test.
 *
 * ```kotlin
 * @Test
 * fun policies() = PolicyTestKit.run("classpath:cosec-policy-test/orders-policy-test.json").assertPasses()
 * ```
 */
object PolicyTestKit {
    private val resourceResolver = PathMatchingResourcePatternResolver()

    fun load(vararg locations: String): List<PolicyTestSuite> =
        resources(
            locations.toList()
        ).map { CoSecJsonSerializer.readValue(it.contentAsByteArray, PolicyTestSuite::class.java) }

    fun run(vararg locations: String): PolicyTestReport {
        val suites = load(*locations)
        require(suites.isNotEmpty()) { "No policy test suites found at ${locations.toList()}." }
        return PolicyTestReport(suites.flatMap(::run))
    }

    fun run(suite: PolicyTestSuite): List<PolicyTestResult> {
        val policies = resources(
            suite.policies
        ).map { CoSecJsonSerializer.readValue(it.contentAsByteArray, Policy::class.java) }
        val appPermissions = resources(suite.appPermissions)
            .map { CoSecJsonSerializer.readValue(it.contentAsByteArray, AppPermission::class.java) }
            .associateBy { it.id }
        val explainer = AuthorizationExplainer(
            InMemoryPolicyRepository(policies),
            InMemoryAppRolePermissionRepository(appPermissions, suite.roles)
        )
        return suite.cases.map { case ->
            val explanation = checkNotNull(
                explainer.explain(case.request.toRequest(), case.principal.toPrincipal(), ExplainOptions()).block(),
            )
            val failure = failureOf(case.expect, explanation)
            PolicyTestResult(suite.name, case.name, failure == null, failure ?: "passed", explanation)
        }
    }

    private fun failureOf(expect: PolicyTestExpectation, explanation: Explanation): String? {
        val actual = actualResult(explanation)
        val decidedBy = explanation.decidedBy
        val problems = buildList {
            if (explanation.status == SecurityStatus.ERROR) {
                add("authorization failed with an error")
            }
            if (actual != expect.result) {
                add("expected ${expect.result}, got $actual")
            }
            expect.policyId?.let { if (it != decidedBy?.policyId) add("expected policy [$it]") }
            expect.statementName?.let { if (it != decidedBy?.statementName) add("expected statement [$it]") }
            expect.roleId?.let { if (it != decidedBy?.roleId) add("expected role [$it]") }
            expect.permissionId?.let { if (it != decidedBy?.permissionId) add("expected permission [$it]") }
            explanation.warnings.filter { it.code == ExplainWarningCode.TRACE_INCONSISTENT }.forEach { add(it.message) }
        }
        if (problems.isEmpty()) {
            return null
        }
        val warnings = explanation.warnings.takeIf { it.isNotEmpty() }?.joinToString(
            prefix = "; warnings: "
        ) { it.message }.orEmpty()
        return problems.joinToString("; ") + "; decided by ${decidedBy.describe()}" + warnings
    }

    private fun actualResult(explanation: Explanation): ExpectedResult = when {
        explanation.authorized -> ExpectedResult.ALLOW
        explanation.decidedBy != null -> ExpectedResult.EXPLICIT_DENY
        else -> ExpectedResult.IMPLICIT_DENY
    }

    private fun DecisionRef?.describe(): String {
        if (this == null) {
            return "no rule (nothing matched)"
        }
        return listOfNotNull(
            layer.name,
            policyId?.let { "policy [$it]" },
            statementIndex?.let { "statement [$it]" },
            roleId?.let { "role [$it]" },
            permissionId?.let { "permission [$it]" },
            statementName?.let { "'$it'" },
        ).joinToString(" ")
    }

    private fun resources(locations: List<String>): List<Resource> =
        locations.flatMap { location ->
            val found = try {
                resourceResolver.getResources(location).filter { it.exists() }
            } catch (_: FileNotFoundException) {
                emptyList()
            }
            require(found.isNotEmpty()) { "Nothing found at [$location]." }
            found
        }

    private class InMemoryPolicyRepository(private val policies: List<Policy>) : PolicyRepository {
        override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(policies.filter { it.type == PolicyType.GLOBAL })
        override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> =
            Mono.just(policies.filter { it.id in policyIds })

        override fun setPolicy(policy: Policy): Mono<Void> = Mono.error(UnsupportedOperationException())
    }

    private class InMemoryAppRolePermissionRepository(
        private val appPermissions: Map<String, AppPermission>,
        private val roles: Map<String, Set<String>>
    ) : AppRolePermissionRepository {
        override fun getAppRolePermission(
            appId: String,
            spaceId: String,
            roleIds: Set<RoleId>
        ): Mono<AppRolePermission> {
            val appPermission = appPermissions[appId] ?: return Mono.empty()
            val grants = roleIds.mapNotNull { roleId -> roles[roleId]?.let { RolePermissionData(roleId, it) } }
            return Mono.just(AppRolePermissionData(appPermission, grants))
        }
    }
}
