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

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.permission.MissingPermissionException
import me.ahoo.cosec.api.permission.Permission
import me.ahoo.cosec.api.permission.PermissionId
import me.ahoo.cosec.api.permission.RolePermission
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.engine.Guard
import me.ahoo.cosec.engine.Rule
import me.ahoo.cosec.engine.RuleGroup
import me.ahoo.cosec.engine.RuleMatch
import me.ahoo.cosec.engine.RuleVisitor
import me.ahoo.cosec.engine.UnresolvedGrant
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.requiresBlocking
import me.ahoo.cosec.api.policy.Effect as PolicyEffect

/*
 * The existing model as engine input (RFC 0002 §3.3). These are CoSec's internal adapter contract: every entry is
 * visited in source order, both passes visit the same entries, and forEachRule returns true exactly when the visitor
 * asked to stop. Nothing here catches an exception; the engine decides what a failure means.
 */

internal fun PolicyEffect.toEngine(): Effect = when (this) {
    PolicyEffect.ALLOW -> Effect.ALLOW
    PolicyEffect.DENY -> Effect.DENY
}

/** A policy condition or app condition. Runs with no path variables, in the statement-scoped context. */
internal class ConditionGuard(private val condition: ConditionMatcher) : Guard {
    override fun matches(request: AuthzRequest): Boolean {
        val facts = request.host as LegacyFacts
        return condition.match(facts.request, facts.beginStatement())
    }
}

/**
 * A policy statement ([policy] set, [id] null) or a role's granted permission ([policy] null, [id] the permission id).
 * Stateless, so cached rules are shared by concurrent evaluations.
 */
internal class StatementRule(
    val statement: Statement,
    val policy: Policy?,
    override val index: Int,
    override val id: String?,
) : Rule {
    override val effect: Effect = statement.effect.toEngine()
    override val name: String = statement.name

    /**
     * Only the CoSec data classes are known to verify as [Statement.verify]'s default does. Another implementation
     * may override it, so it is called instead, and a miss cannot tell the action from the condition (RFC 0002 X5).
     */
    private val plain = statement is StatementData || statement is PermissionData
    private val decisive = if (effect == Effect.ALLOW) VerifyResult.ALLOW else VerifyResult.EXPLICIT_DENY

    override fun match(request: AuthzRequest): RuleMatch {
        val facts = request.host as LegacyFacts
        val context = facts.beginStatement()
        val legacyRequest = facts.request
        val match = when {
            !plain -> if (statement.verify(legacyRequest, context) == decisive) RuleMatch.MATCHED else RuleMatch.CONDITION_MISS
            !statement.action.match(legacyRequest, context) -> RuleMatch.ACTION_MISS
            !statement.condition.match(legacyRequest, context) -> RuleMatch.CONDITION_MISS
            else -> RuleMatch.MATCHED
        }
        if (match == RuleMatch.MATCHED) {
            facts.ruleMatched(this)
        }
        return match
    }
}

/** A policy of the global or principal layer. [PolicyData] caches its own; other policies are adapted per request. */
internal class PolicyGroup(val policy: Policy) : RuleGroup {
    override val id: String = policy.id
    override val guard: Guard = ConditionGuard(policy.condition)
    private val rules: Array<StatementRule> = policy.statements.let { statements ->
        Array(statements.size) { index -> StatementRule(statements[index], policy, index, null) }
    }

    override fun forEachRule(visitor: RuleVisitor): Boolean {
        for (rule in rules) {
            if (visitor.visit(rule, null)) {
                return true
            }
        }
        return false
    }
}

/**
 * The groups of a policy layer, as a view over the repository's list: adapting is per policy, not per request, for
 * [PolicyData], and once per request for any other policy.
 */
internal class PolicyGroupList(val policies: List<Policy>) : AbstractList<RuleGroup>(), RandomAccess {
    private var adapted: Array<PolicyGroup?>? = null

    override val size: Int get() = policies.size

    override fun get(index: Int): RuleGroup {
        val policy = policies[index]
        if (policy is PolicyData) {
            return policy.engineGroup()
        }
        val adapted = adapted ?: arrayOfNulls<PolicyGroup>(policies.size).also { adapted = it }
        return adapted[index] ?: PolicyGroup(policy).also { adapted[index] = it }
    }

    val requiresBlocking: Boolean
        get() {
            for (i in policies.indices) {
                if (policies[i].requiresBlocking) {
                    return true
                }
            }
            return false
        }
}

/**
 * The rules of an app's permission catalog, by grant. [AppPermissionData] caches its own, so the role layer adapts
 * nothing per request but its one [AppPermissionGroup]; any other app permission is adapted once per request.
 */
internal class AppPermissionRules(val appPermission: AppPermission) {
    val guard: Guard = ConditionGuard(appPermission.condition)
    val requiresBlocking: Boolean = appPermission.requiresBlocking
    private val permissionIndexer: Map<PermissionId, Permission> = appPermission.permissionIndexer

    /** What a [AppRolePermission.ALL_PERMISSION_ID] grant visits: the whole catalog, in its order. */
    private val wildcard: Array<StatementRule> = permissionIndexer.values.let { permissions ->
        val iterator = permissions.iterator()
        Array(permissions.size) { index ->
            val permission = iterator.next()
            StatementRule(permission, null, index, permission.id)
        }
    }

    private val byId: Map<PermissionId, PermissionRules> =
        permissionIndexer.mapValuesTo(HashMap(permissionIndexer.size * 2)) { PermissionRules(it.value) }

    fun forEachWildcard(roleId: String, visitor: RuleVisitor): Boolean {
        for (rule in wildcard) {
            if (visitor.visit(rule, roleId)) {
                return true
            }
        }
        return false
    }

    /** The rule for [permissionId] granted at [position] of its role's grant list, or null when the app has none. */
    fun granted(permissionId: PermissionId, position: Int): StatementRule? = byId[permissionId]?.at(position)

    /** The exception 7.0 throws when the deny pass reaches an id [rolePermission] grants and the app does not define. */
    fun missingPermission(rolePermission: RolePermission): MissingPermissionException = MissingPermissionException(
        appPermission.id,
        rolePermission.id,
        rolePermission.permissions.filterNotTo(LinkedHashSet()) { permissionIndexer.containsKey(it) },
    )
}

/**
 * One permission's rules by grant position, created when first granted there. The rule carries its position in the
 * grant list ([Rule.index]), so a permission granted at different positions has a rule for each.
 */
internal class PermissionRules(private val permission: Permission) {
    @Volatile
    private var byPosition: Array<StatementRule?> = NONE

    fun at(position: Int): StatementRule {
        if (position >= CACHED_POSITIONS) {
            return StatementRule(permission, null, position, permission.id)
        }
        val cached = byPosition
        if (position < cached.size) {
            cached[position]?.let { return it }
        }
        // Copy on write: a lost race only builds the same rule twice.
        val rule = StatementRule(permission, null, position, permission.id)
        val grown = cached.copyOf(maxOf(cached.size, position + 1))
        grown[position] = rule
        byPosition = grown
        return rule
    }

    private companion object {
        val NONE = arrayOfNulls<StatementRule>(0)

        /** Grant lists longer than this adapt their tail per request rather than cache a rule per position. */
        const val CACHED_POSITIONS = 64
    }
}

/**
 * The role layer's one group: the app's condition, then the principal's role grants in [rolePermissions] order, as
 * 7.0's `forEachPermission` visits them. A wildcard grant visits the whole catalog; any other visits each granted id
 * and, for an id the app does not define, an [UnresolvedGrant] where the grant is (S10a).
 */
internal class AppPermissionGroup(
    private val rules: AppPermissionRules,
    private val rolePermissions: List<RolePermission>,
) : RuleGroup {
    override val id: String = rules.appPermission.id
    override val guard: Guard get() = rules.guard
    val requiresBlocking: Boolean get() = rules.requiresBlocking

    /** Built at most once per grant, so both passes and the trace see the same entry and exception. */
    private var unresolved: ArrayList<Unresolved>? = null

    override fun forEachRule(visitor: RuleVisitor): Boolean {
        for (rolePermission in rolePermissions) {
            val roleId = rolePermission.id
            val permissions = rolePermission.permissions
            if (permissions.contains(AppRolePermission.ALL_PERMISSION_ID)) {
                if (rules.forEachWildcard(roleId, visitor)) {
                    return true
                }
                // The wildcard ends this role's grants, not the layer: 7.0 returns from forEachPermission only.
                continue
            }
            var position = 0
            for (permissionId in permissions) {
                val entry = rules.granted(permissionId, position) ?: unresolved(rolePermission, permissionId)
                if (visitor.visit(entry, roleId)) {
                    return true
                }
                position++
            }
        }
        return false
    }

    private fun unresolved(rolePermission: RolePermission, permissionId: PermissionId): UnresolvedGrant {
        val unresolved = unresolved ?: ArrayList<Unresolved>().also { unresolved = it }
        unresolved.firstOrNull { it.rolePermission === rolePermission && it.grant.permissionId == permissionId }
            ?.let { return it.grant }
        val grant = UnresolvedGrant(permissionId, rules.missingPermission(rolePermission))
        unresolved.add(Unresolved(rolePermission, grant))
        return grant
    }

    private class Unresolved(val rolePermission: RolePermission, val grant: UnresolvedGrant)
}
