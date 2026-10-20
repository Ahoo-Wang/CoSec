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
import me.ahoo.cosec.api.principal.SpacedRoleId
import me.ahoo.cosec.authorization.RoleScope
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
 * @param roleId for a role permission, the role without its `@space` suffix
 * @param spaceId for a role permission, the space the grant is made in (`""` for the default space)
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
    val permissionId: String? = null,
    val spaceId: String? = null
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
 * Role grants follow authorization: a grant belongs to one app, role and space, and applies to a principal only
 * through a role it holds in that space (see [RoleScope]).
 *
 * @param roles role grants per app: app id to role to permission ids (`*` grants all). A role written `role@space`
 * is granted in `space` only, a role without a suffix in the default space only, as in principals' roles and Redis
 * keys. A malformed role (empty role or space, more than one `@`) fails construction.
 */
class AccessAnalyzer(
    private val policies: List<Policy>,
    private val appPermissions: List<AppPermission> = emptyList(),
    private val roles: Map<String, Map<String, Set<String>>> = emptyMap()
) {
    companion object {
        private val TEMPLATE = Regex("#\\{[^}]*}")

        /**
         * Builds an analyzer from JSON resources, failing on a location that matches no file, a file that cannot be read
         * and two files with the same id.
         */
        fun load(
            policyLocations: List<String>,
            appPermissionLocations: List<String> = emptyList(),
            roles: Map<String, Map<String, Set<String>>> = emptyMap()
        ): AccessAnalyzer = AccessAnalyzer(
            policies = JsonResources.readAll(policyLocations, Policy::class.java) { it.id },
            appPermissions = JsonResources.readAll(appPermissionLocations, AppPermission::class.java) { it.id },
            roles = roles,
        )
    }

    private val anonymous = SimpleSecurityContext(SimpleTenantPrincipal.ANONYMOUS).markPolicyEvaluation()

    private val grants: Map<String, Map<SpacedRoleId, Set<String>>> = roles.mapValues { (appId, appRoles) ->
        appRoles.mapKeys { (role, _) ->
            requireNotNull(RoleScope.parse(role)) {
                "Role [$role] of app [$appId] is malformed: write `role` or `role@space`."
            }
        }
    }

    /**
     * The rules whose action matches [request]. Role permissions are taken from the grants made in the request's app
     * and space.
     */
    fun whoCan(request: Request): AccessPaths {
        val spaceId = request.spaceId
        val rules = policyRules(policies).filter { it.first.matchesAction(request) } +
            roleRules(request.appId) { it.spaceId == spaceId }.filter { it.first.matchesAction(request) }
        val (allows, denies) = rules.map { it.second }.partition { it.effect == Effect.ALLOW }
        return AccessPaths(allows, denies)
    }

    /**
     * Every rule [principal] can reach: global policies, the policies it lists, and the permissions granted to its
     * roles in [appId] (every app when null) and [spaceId] (every space it holds a role in when null). A role
     * `role@space` reaches only the grants made to `role` in `space`, a role without a suffix only those in the
     * default space.
     */
    fun grantsOf(principal: CoSecPrincipal, appId: String? = null, spaceId: String? = null): List<AccessRule> {
        val reachable = policies.filter { it.type == PolicyType.GLOBAL || it.id in principal.policies }
        val apps = appId?.let { listOf(it) } ?: appPermissions.map { it.id }
        val held = principal.roles.mapNotNullTo(HashSet(), RoleScope::parse)
        return policyRules(reachable).map { it.second } +
            apps.flatMap { app ->
                roleRules(app) { it in held && (spaceId == null || it.spaceId == spaceId) }.map { it.second }
            }
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

    private fun roleRules(
        appId: String,
        applies: (SpacedRoleId) -> Boolean
    ): List<Pair<Statement, AccessRule>> {
        val appPermission = appPermissions.firstOrNull { it.id == appId } ?: return emptyList()
        val indexer = appPermission.permissionIndexer
        return grants[appId].orEmpty()
            .filterKeys(applies)
            .flatMap { (role, permissionIds) ->
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
                        roleId = role.roleId,
                        permissionId = permission.id,
                        spaceId = role.spaceId,
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
