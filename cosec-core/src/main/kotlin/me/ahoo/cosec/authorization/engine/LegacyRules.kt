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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
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
    val requiresBlocking: Boolean = policy.requiresBlocking
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

    /** The rules of [permissionId] by grant position, or null when the app does not define it. */
    fun rulesOf(permissionId: PermissionId): PermissionRules? = byId[permissionId]

    /** The exception 7.0 throws when the deny pass reaches an id [rolePermission] grants and the app does not define. */
    fun missingPermission(rolePermission: RolePermission): MissingPermissionException = MissingPermissionException(
        appPermission.id,
        rolePermission.id,
        rolePermission.permissions.filterNotTo(LinkedHashSet()) { permissionIndexer.containsKey(it) },
    )
}

/**
 * One permission's rules by grant position. The rule carries its position in the grant list ([Rule.index]), so a
 * permission granted at different positions has a rule for each.
 *
 * Shared by every request, so it is race-free: each position below [CACHED_POSITIONS] gets exactly one rule, ever,
 * which both passes of an evaluation (and the trace, which aligns them by identity) see. A position beyond it is
 * memoized per request by its [AppPermissionGroup] instead.
 */
internal class PermissionRules(private val permission: Permission) {
    private val byPosition = AtomicReference<AtomicReferenceArray<StatementRule>?>()

    fun at(position: Int): StatementRule {
        require(position < CACHED_POSITIONS) { "Position $position is not cached." }
        val rules = byPosition.get() ?: AtomicReferenceArray<StatementRule>(CACHED_POSITIONS).let { created ->
            if (byPosition.compareAndSet(null, created)) created else checkNotNull(byPosition.get())
        }
        rules.get(position)?.let { return it }
        val rule = uncached(position)
        return if (rules.compareAndSet(position, null, rule)) rule else checkNotNull(rules.get(position))
    }

    fun uncached(position: Int): StatementRule = StatementRule(permission, null, position, permission.id)

    companion object {
        /** Grant positions with a shared rule; longer grant lists build their tail once per request. */
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

    /** Rules for grant positions beyond the shared cache, built once per request for the same reason. */
    private var tail: HashMap<Long, StatementRule>? = null

    override fun forEachRule(visitor: RuleVisitor): Boolean {
        var roleIndex = 0
        for (rolePermission in rolePermissions) {
            val role = roleIndex++
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
                val entry = granted(role, permissionId, position) ?: unresolved(rolePermission, permissionId)
                if (visitor.visit(entry, roleId)) {
                    return true
                }
                position++
            }
        }
        return false
    }

    private fun granted(role: Int, permissionId: PermissionId, position: Int): StatementRule? {
        val permissionRules = rules.rulesOf(permissionId) ?: return null
        if (position < PermissionRules.CACHED_POSITIONS) {
            return permissionRules.at(position)
        }
        val tail = tail ?: HashMap<Long, StatementRule>().also { tail = it }
        return tail.getOrPut((role.toLong() shl Int.SIZE_BITS) or position.toLong()) {
            permissionRules.uncached(position)
        }
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
