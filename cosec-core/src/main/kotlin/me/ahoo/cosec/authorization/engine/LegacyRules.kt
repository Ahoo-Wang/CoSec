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
internal class AppPermissionRules(
    val appPermission: AppPermission,
    /**
     * Whether these rules are cached on the app permission and so shared by every request. Only shared rules keep a
     * rule per grant position across requests; rules built for one request leave every position to its group's memo.
     */
    val shared: Boolean = false,
) {
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

    /**
     * Each permission's rules by grant position. A rule carries its position in its role's grant list ([Rule.index]),
     * so a permission granted at different positions has a rule for each, and each position below [CACHED_POSITIONS]
     * has exactly one rule, ever: both passes of an evaluation, and the trace, which aligns them by identity, see the
     * same one. A position beyond them (any position, when the rules are not shared) is memoized per request by its
     * [AppPermissionGroup] instead.
     *
     * The map's value is the array itself, allocated with the rules and never replaced, and its slots are read without
     * synchronization: the role layer's deny pass visits every grant, so each dependent load or atomic read on this path
     * shows in its throughput (RFC 0002 PR-3 measurement, `role-heavy`). A reader that finds a slot empty takes the lock
     * in [install]; a rule it does find is complete, since all its fields are final.
     */
    private val byId: Map<PermissionId, Array<StatementRule?>> =
        permissionIndexer.mapValuesTo(HashMap(permissionIndexer.size * 2)) {
            if (shared) arrayOfNulls(CACHED_POSITIONS) else NO_SLOTS
        }

    fun forEachWildcard(roleId: String, visitor: RuleVisitor): Boolean {
        for (rule in wildcard) {
            if (visitor.visit(rule, roleId)) {
                return true
            }
        }
        return false
    }

    /**
     * The shared rules of [permissionId] by grant position, or null when the app does not define it. A position the
     * array does not reach is the group's to memoize; an empty slot is filled by [install].
     */
    fun slotsOf(permissionId: PermissionId): Array<StatementRule?>? = byId[permissionId]

    fun install(permissionId: PermissionId, slots: Array<StatementRule?>, position: Int): StatementRule =
        synchronized(slots) {
            slots[position] ?: uncached(permissionId, position).also { slots[position] = it }
        }

    /** A rule of [permissionId] at [position] built for one request, or null when the app does not define it. */
    fun uncached(permissionId: PermissionId, position: Int): StatementRule {
        val permission = checkNotNull(permissionIndexer[permissionId]) { "Permission $permissionId is not defined." }
        return StatementRule(permission, null, position, permission.id)
    }

    /** The exception 7.0 throws when the deny pass reaches an id [rolePermission] grants and the app does not define. */
    fun missingPermission(rolePermission: RolePermission): MissingPermissionException = MissingPermissionException(
        appPermission.id,
        rolePermission.id,
        rolePermission.permissions.filterNotTo(LinkedHashSet()) { permissionIndexer.containsKey(it) },
    )

    companion object {
        /** Grant positions with a shared rule; longer grant lists build their tail once per request. */
        const val CACHED_POSITIONS = 64

        private val NO_SLOTS = arrayOfNulls<StatementRule>(0)
    }
}

/**
 * The role layer's one group: the app's condition, then the principal's role grants in [rolePermissions] order, as
 * 7.0's `forEachPermission` visits them. A wildcard grant visits the whole catalog; any other visits each granted id
 * and, for an id the app does not define, an [UnresolvedGrant] where the grant is (S10a).
 *
 * One per request, used by one evaluation at a time: its memos are not synchronized.
 */
internal class AppPermissionGroup(
    private val rules: AppPermissionRules,
    val rolePermissions: List<RolePermission>,
) : RuleGroup {
    val appPermission: AppPermission get() = rules.appPermission
    override val id: String = rules.appPermission.id
    override val guard: Guard get() = rules.guard
    val requiresBlocking: Boolean get() = rules.requiresBlocking

    /** Built at most once per grant, so both passes and the trace see the same entry and exception. */
    private var unresolved: ArrayList<Unresolved>? = null

    /**
     * Rules for grant positions beyond the shared slots, built
     * once per request for the same reason.
     */
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
                val slots = rules.slotsOf(permissionId)
                val entry = when {
                    slots == null -> unresolved(rolePermission, permissionId)
                    position < slots.size -> slots[position] ?: rules.install(permissionId, slots, position)
                    else -> tail(role, permissionId, position)
                }
                if (visitor.visit(entry, roleId)) {
                    return true
                }
                position++
            }
        }
        return false
    }

    /** A grant position beyond the shared slots: one permission of one role, so the role is part of the key. */
    private fun tail(role: Int, permissionId: PermissionId, position: Int): StatementRule {
        val tail = tail ?: HashMap<Long, StatementRule>().also { tail = it }
        return tail.getOrPut((role.toLong() shl Int.SIZE_BITS) or position.toLong()) {
            rules.uncached(permissionId, position)
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
