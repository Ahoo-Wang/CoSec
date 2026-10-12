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

package me.ahoo.cosec.analysis

import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.RequestMatcher
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.explain.MatcherRef
import me.ahoo.cosec.policy.condition.AllConditionMatcherFactory
import me.ahoo.cosec.policy.condition.CONDITION_MATCHER_NEGATE_KEY
import me.ahoo.cosec.policy.markPolicyEvaluation
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.serialization.JsonResources

enum class AccessSource {
    /** A global policy: applies to every principal. */
    GLOBAL_POLICY,

    /** A policy that applies to principals that list its id. */
    PRINCIPAL_POLICY,

    /** A permission granted to a role in an app. */
    ROLE_PERMISSION,
}

/**
 * One rule that can take part in a decision.
 *
 * @param conditional whether a condition (of the statement, its policy or its app permission) must also match, so
 * the rule applies only to some principals or requests
 * @param templated whether the action depends on the caller (a `#{...}` template). Such rules are matched with each
 * template widened to any segment, so they are listed when some caller could match.
 */
data class AccessRule(
    val source: AccessSource,
    val effect: Effect,
    val name: String,
    val action: MatcherRef,
    val condition: MatcherRef,
    val conditional: Boolean,
    val templated: Boolean,
    val policyId: String? = null,
    val statementIndex: Int? = null,
    val appId: String? = null,
    val roleId: String? = null,
    val permissionId: String? = null
)

/**
 * The rules whose action matches a request. Whoever holds an [allows] rule (everyone for a global policy, the
 * principals listing a policy, the members of a role) may be allowed, unless one of the [denies] also applies.
 */
data class AccessPaths(
    val allows: List<AccessRule>,
    val denies: List<AccessRule>
)

/**
 * Answers access-review questions over a set of policies, app permissions and role grants: who can make a request
 * ([whoCan]), and what a principal can reach ([grantsOf]).
 *
 * Repositories cannot list every policy or role, so the analysis works on the data it is given, typically the same
 * files the application loads. It matches actions only; conditions are reported, not evaluated, because they
 * depend on the caller and the request. When in doubt it lists a rule rather than leaving it out: an access review
 * must not miss a grant.
 *
 * @param roles role grants per app: app id to role id to permission ids (`*` grants all)
 */
class AccessAnalyzer(
    private val policies: List<Policy>,
    private val appPermissions: List<AppPermission> = emptyList(),
    private val roles: Map<String, Map<String, Set<String>>> = emptyMap()
) {
    companion object {
        private val TEMPLATE = Regex("#\\{[^}]*}")

        /**
         * Builds an analyzer from JSON resources, failing on any that cannot be read.
         */
        fun load(
            policyLocations: List<String>,
            appPermissionLocations: List<String> = emptyList(),
            roles: Map<String, Map<String, Set<String>>> = emptyMap()
        ): AccessAnalyzer = AccessAnalyzer(
            policies = JsonResources.readAll(policyLocations, Policy::class.java),
            appPermissions = JsonResources.readAll(appPermissionLocations, AppPermission::class.java),
            roles = roles,
        )
    }

    private val anonymous = SimpleSecurityContext(SimpleTenantPrincipal.ANONYMOUS).markPolicyEvaluation()

    /**
     * The rules whose action matches [request]. Role permissions are taken from the request's app.
     */
    fun whoCan(request: Request): AccessPaths {
        val rules = policyRules(policies).filter { it.first.matchesAction(request) } +
            roleRules(request.appId).filter { it.first.matchesAction(request) }
        val (allows, denies) = rules.map { it.second }.partition { it.effect == Effect.ALLOW }
        return AccessPaths(allows, denies)
    }

    /**
     * Every rule [principal] can reach: global policies, the policies it lists, and the permissions of its roles in
     * [appId] (every app when null).
     */
    fun grantsOf(principal: CoSecPrincipal, appId: String? = null): List<AccessRule> {
        val reachable = policies.filter { it.type == PolicyType.GLOBAL || it.id in principal.policies }
        val apps = appId?.let { listOf(it) } ?: appPermissions.map { it.id }
        return policyRules(reachable).map { it.second } +
            apps.flatMap { app -> roleRules(app, principal.roles).map { it.second } }
    }

    private fun policyRules(policies: List<Policy>): List<Pair<Statement, AccessRule>> =
        policies.flatMap { policy ->
            val source = if (policy.type == PolicyType.GLOBAL) AccessSource.GLOBAL_POLICY else AccessSource.PRINCIPAL_POLICY
            policy.statements.mapIndexed { index, statement ->
                statement to AccessRule(
                    source = source,
                    effect = statement.effect,
                    name = statement.name,
                    action = statement.action.toRef(),
                    condition = statement.condition.toRef(),
                    conditional = !policy.condition.isAlways() || !statement.condition.isAlways(),
                    templated = statement.action.isTemplated(),
                    policyId = policy.id,
                    statementIndex = index,
                )
            }
        }

    private fun roleRules(appId: String, roleIds: Set<String>? = null): List<Pair<Statement, AccessRule>> {
        val appPermission = appPermissions.firstOrNull { it.id == appId } ?: return emptyList()
        val indexer = appPermission.permissionIndexer
        return roles[appId].orEmpty()
            .filterKeys { roleIds == null || it in roleIds }
            .flatMap { (roleId, permissionIds) ->
                val granted = if (AppRolePermission.ALL_PERMISSION_ID in permissionIds) {
                    indexer.values.toList()
                } else {
                    permissionIds.mapNotNull { indexer[it] }
                }
                granted.map { permission ->
                    permission to AccessRule(
                        source = AccessSource.ROLE_PERMISSION,
                        effect = permission.effect,
                        name = permission.name,
                        action = permission.action.toRef(),
                        condition = permission.condition.toRef(),
                        conditional = !appPermission.condition.isAlways() || !permission.condition.isAlways(),
                        templated = permission.action.isTemplated(),
                        appId = appId,
                        roleId = roleId,
                        permissionId = permission.id,
                    )
                }
            }
    }

    /**
     * A templated action cannot be resolved without the caller, so each `#{...}` is widened to a `*` segment: the
     * rule is listed whenever some caller could match. If the widened action cannot be built, it is listed anyway.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun Statement.matchesAction(request: Request): Boolean {
        if (!action.isTemplated()) {
            return action.match(request, anonymous)
        }
        return try {
            val widened = CoSecJsonSerializer.writeValueAsString(action).replace(TEMPLATE, "*")
            CoSecJsonSerializer.readValue(widened, ActionMatcher::class.java).match(request, anonymous)
        } catch (_: RuntimeException) {
            true
        }
    }

    private fun RequestMatcher.isTemplated(): Boolean =
        CoSecJsonSerializer.writeValueAsString(configuration).contains("#{")

    private fun ConditionMatcher.isAlways(): Boolean =
        type == AllConditionMatcherFactory.TYPE && configuration.get(CONDITION_MATCHER_NEGATE_KEY)?.asBoolean() != true

    private fun RequestMatcher.toRef(): MatcherRef = MatcherRef(type, configuration)
}
