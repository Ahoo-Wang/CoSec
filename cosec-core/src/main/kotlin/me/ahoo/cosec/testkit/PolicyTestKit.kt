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
import me.ahoo.cosec.api.principal.SpacedRoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.authorization.RootPrincipalMatcher
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
import me.ahoo.cosec.serialization.JsonResources
import reactor.core.publisher.Mono

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
 * Role grants are written as in principals' roles and Redis keys: a role `role@space` is granted in `space` only, a
 * role without a suffix in the default space only. As in authorization, a case's principal reaches a grant only
 * through a role it holds in the request's space. A malformed role (empty role or space, more than one `@`) fails
 * the run, and so does a grant naming a permission id its app does not define, since authorization fails closed on
 * it. Grants in [roles] are made in every app, so each app must define them; grant app-specific ids in [appRoles].
 *
 * @param policies resource patterns of policy files, e.g. `classpath:cosec-policy/` followed by a file pattern
 * @param appPermissions resource patterns of app permission files
 * @param roles role grants made in every app permission: role to permission ids (`*` grants all)
 * @param appRoles role grants made in one app: app id to role to permission ids, added to [roles]. Each app must be
 * defined by one of [appPermissions].
 */
data class PolicyTestSuite(
    val name: String = "",
    val policies: List<String> = emptyList(),
    val appPermissions: List<String> = emptyList(),
    val roles: Map<String, Set<String>> = emptyMap(),
    val cases: List<PolicyTestCase> = emptyList(),
    val appRoles: Map<String, Map<String, Set<String>>> = emptyMap()
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
 *
 * No principal is root unless a [RootPrincipalMatcher] is passed, as in the starter
 * (`cosec.authorization.root-enabled` is off by default), so a case for the root id runs through the policies like
 * any other principal.
 */
object PolicyTestKit {
    fun load(vararg locations: String): List<PolicyTestSuite> =
        JsonResources.readAll(locations.toList(), PolicyTestSuite::class.java)

    fun run(vararg locations: String): PolicyTestReport = run(locations.toList())

    fun run(
        locations: List<String>,
        rootPrincipalMatcher: RootPrincipalMatcher = RootPrincipalMatcher.NONE
    ): PolicyTestReport = run(locations, rootPrincipalMatcher, explainFromTrace = false)

    fun run(
        suite: PolicyTestSuite,
        rootPrincipalMatcher: RootPrincipalMatcher = RootPrincipalMatcher.NONE
    ): List<PolicyTestResult> = run(suite, rootPrincipalMatcher, explainFromTrace = false)

    /** [explainFromTrace]: explain from the engine's trace (RFC 0002 §5.2); only tests set it until PR-6. */
    internal fun run(
        locations: List<String>,
        rootPrincipalMatcher: RootPrincipalMatcher,
        explainFromTrace: Boolean
    ): PolicyTestReport {
        val suites = load(*locations.toTypedArray())
        require(suites.isNotEmpty()) { "No policy test suites found at $locations." }
        return PolicyTestReport(suites.flatMap { run(it, rootPrincipalMatcher, explainFromTrace) })
    }

    internal fun run(
        suite: PolicyTestSuite,
        rootPrincipalMatcher: RootPrincipalMatcher,
        explainFromTrace: Boolean
    ): List<PolicyTestResult> {
        val policies = JsonResources.readAll(suite.policies, Policy::class.java) { it.id }
        val appPermissions = JsonResources.readAll(
            suite.appPermissions,
            AppPermission::class.java
        ) { it.id }.associateBy { it.id }
        val policyRepository = InMemoryPolicyRepository(policies)
        val appRolePermissionRepository =
            InMemoryAppRolePermissionRepository(appPermissions, grantsOf(suite, appPermissions))
        val explainer = if (explainFromTrace) {
            AuthorizationExplainer.fromTrace(
                policyRepository,
                appRolePermissionRepository,
                rootPrincipalMatcher = rootPrincipalMatcher,
            )
        } else {
            AuthorizationExplainer(
                policyRepository,
                appRolePermissionRepository,
                rootPrincipalMatcher = rootPrincipalMatcher
            )
        }
        return suite.cases.map { case ->
            val explanation = checkNotNull(
                explainer.explain(case.request.toRequest(), case.principal.toPrincipal(), ExplainOptions()).block(),
            )
            val failure = failureOf(case.expect, explanation)
            PolicyTestResult(suite.name, case.name, failure == null, failure ?: "passed", explanation)
        }
    }

    /**
     * The suite's grants per app, keyed by role and space.
     */
    private fun grantsOf(
        suite: PolicyTestSuite,
        appPermissions: Map<String, AppPermission>
    ): Map<String, Map<SpacedRoleId, Set<String>>> {
        val appIds = appPermissions.keys
        suite.appRoles.keys.forEach { appId ->
            require(appId in appIds) {
                "Suite [${suite.name}] grants roles in app [$appId], but no app permission file defines it."
            }
        }
        val everyApp = parseRoles(suite, suite.roles)
        return appIds.associateWith { appId ->
            val grants = everyApp.toMutableMap()
            parseRoles(suite, suite.appRoles[appId].orEmpty()).forEach { (role, permissionIds) ->
                grants.merge(role, permissionIds, Set<String>::plus)
            }
            requireDefined(suite, appPermissions.getValue(appId), grants)
            grants
        }
    }

    private fun requireDefined(
        suite: PolicyTestSuite,
        appPermission: AppPermission,
        grants: Map<SpacedRoleId, Set<String>>
    ) {
        val permissionIndexer = appPermission.permissionIndexer
        grants.forEach { (role, permissionIds) ->
            if (AppRolePermission.ALL_PERMISSION_ID in permissionIds) {
                return@forEach
            }
            val missing = permissionIds.filterNot { permissionIndexer.containsKey(it) }
            require(missing.isEmpty()) {
                "Suite [${suite.name}] grants role [$role] permissions $missing, which app [${appPermission.id}] " +
                    "does not define."
            }
        }
    }

    private fun parseRoles(suite: PolicyTestSuite, roles: Map<String, Set<String>>): Map<SpacedRoleId, Set<String>> =
        roles.mapKeys { (role, _) ->
            requireNotNull(RoleScope.parse(role)) {
                "Suite [${suite.name}] grants malformed role [$role]: write `role` or `role@space`."
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

    private class InMemoryPolicyRepository(private val policies: List<Policy>) : PolicyRepository {
        override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(policies.filter { it.type == PolicyType.GLOBAL })
        override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> =
            Mono.just(policies.filter { it.id in policyIds })

        override fun setPolicy(policy: Policy): Mono<Void> = Mono.error(UnsupportedOperationException())
    }

    /**
     * Returns only the grants made in the requested app and space, as a store keyed by app, role and space does.
     */
    private class InMemoryAppRolePermissionRepository(
        private val appPermissions: Map<String, AppPermission>,
        private val grants: Map<String, Map<SpacedRoleId, Set<String>>>
    ) : AppRolePermissionRepository {
        override fun getAppRolePermission(
            appId: String,
            spaceId: String,
            roleIds: Set<RoleId>
        ): Mono<AppRolePermission> {
            val appPermission = appPermissions[appId] ?: return Mono.empty()
            val appGrants = grants[appId].orEmpty()
            val grants = roleIds.mapNotNull { roleId ->
                appGrants[SpacedRoleId(roleId, spaceId)]?.let { RolePermissionData(roleId, it) }
            }
            return Mono.just(AppRolePermissionData(appPermission, grants))
        }
    }
}
