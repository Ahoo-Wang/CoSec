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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.engine.flat.SubjectAttributes
import me.ahoo.cosec.engine.pattern.PatternContainment
import me.ahoo.cosec.engine.pattern.ResourceContainment
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Attribute.Kind
import me.ahoo.cosec.model.condition.CheckResult
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.ConditionContainment
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.model.condition.Containment
import me.ahoo.cosec.model.condition.IndexKeys
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.condition.Operand
import me.ahoo.cosec.model.condition.ZoneResolver
import me.ahoo.cosec.model.decision.StatementRef

/**
 * Nobody can grant beyond their own rights (RFC 0007 §7.4): splits a change into granted fragments (§7.4.1) and checks
 * each against the grantor's own statements in the published snapshot (§7.4.3).
 *
 * A fragment is [GrantVerdict.Contained] only if one single ALLOW of the grantor
 * 1. applies to the grantor in every `(app, space)` the fragment is granted to, after the fragment's condition has
 *    narrowed them (`refine`, by the index keys of RFC 0005 §4.1);
 * 2. contains its action and its resource ([PatternContainment]);
 * 3. has a condition that, with the grantor's own attributes substituted (`specialize`), is true or is implied by the
 *    fragment's condition ([ConditionContainment.implies]). A condition that reads a variable needs the fragment's
 *    resource to capture it at the same position, and every other resource of the ALLOW to be disjoint from the
 *    fragment's, so the value is the same; one that reads an external attribute is never comparable;
 *
 * and, when the grantor holds that ALLOW through a binding with `expiresAt`, the fragment's own grant ends no later
 * (RC1-3); and no DENY that binds the grantor other than through `Anyone` (Q8) may overlap it. Everything else is
 * [GrantVerdict.Unknown], which goes to approval (§7.5): soundness over completeness (A5, A8). So is a binding whose
 * policy does not resolve (RC1-32).
 *
 * Narrowing (§7.4.4) is [narrows]: a changed ALLOW that only narrows one of the policy's previous ALLOWs grants
 * nothing. Removing bindings and ALLOWs and adding or relaxing DENYs grant nothing either; changing or removing a
 * `protected` entry needs `cosec:unprotect` (Q9), which the analysis reports in [GrantAnalysis.unprotect].
 *
 * Conditions come as written. Both sides are checked with the same [zones], within limits twice the document's
 * ([limits]): a statement's condition and its policy's are combined.
 */
@ExperimentalCoSecApi
class GrantAnalyzer(zones: ZoneResolver = ZoneResolver.FIXED, limits: ConditionLimits = ConditionLimits.DEFAULT) {
    private val checker = ConditionChecker(
        ConditionLimits(
            maxNodes = 2 * limits.maxNodes + 1,
            maxDepth = limits.maxDepth + 1,
            maxInValues = limits.maxInValues,
            maxCidrBlocks = limits.maxCidrBlocks,
            maxLiteralBytes = limits.maxLiteralBytes,
            maxCost = 2 * limits.maxCost + 1,
        ),
        zones,
    )

    /**
     * Analyzes [change] for [grantor] against the published [snapshot]. [trustRoot] is whether the grantor holds
     * `cosec:approve` on the document (§7.3): its fragments are then not analyzed, only reported.
     */
    fun analyze(
        grantor: Grantor,
        snapshot: GrantSnapshot,
        change: GrantChange,
        trustRoot: Boolean = false
    ): GrantAnalysis {
        val fragments = fragments(snapshot, change)
        val unprotect = unprotect(snapshot.tenant(change.tenant), change.candidate)
        if (trustRoot) {
            return GrantAnalysis(fragments.map { it to GrantVerdict.TrustRoot }, unprotect)
        }
        val rights = GrantorRights.of(grantor, snapshot)
        return GrantAnalysis(fragments.map { it to verdict(grantor, rights, it) }, unprotect)
    }

    /**
     * The fragments [change] grants (§7.4.1). Policies resolve in the candidate; a new or changed binding whose policy
     * does not resolve is an [unresolved][GrantFragment.unresolved] fragment (RC1-32).
     *
     * Removing one of the tenant's own policies grants too when another document defines its id (RC1-9): the
     * bindings and attachments that named the tenant's now name that one, which nobody analyzed for them.
     */
    fun fragments(snapshot: GrantSnapshot, change: GrantChange): List<GrantFragment> {
        val before = snapshot.tenant(change.tenant)
        val after = change.candidate
        val fragments = ArrayList<GrantFragment>()
        val changed = after.bindings.filter { binding -> before.bindings.none { same(it, binding) } }
        for (binding in changed) {
            val policy = resolve(snapshot, after, binding)
            if (policy == null) {
                fragments += GrantFragment.unresolved(change.tenant, binding)
                continue
            }
            policy.statements.indices.forEach { split(change.tenant, policy, it, Grantee.Bound(binding), fragments) }
        }
        reresolved(snapshot, change, changed, fragments)
        for (policy in after.policies) {
            changedPolicy(snapshot, change, before.policy(policy.id), policy, changed, fragments)
        }
        return fragments
    }

    /**
     * What re-resolves when the candidate removes one of the tenant's own policies whose id another document defines
     * (RC1-9): every live binding left unchanged that named it grants all of the policy it names now, and so does
     * every attachment of it, when that one is attachable. Nothing is granted when the other is the same policy.
     */
    private fun reresolved(
        snapshot: GrantSnapshot,
        change: GrantChange,
        changed: List<Binding>,
        into: MutableList<GrantFragment>,
    ) {
        val after = change.candidate
        val before = snapshot.tenant(change.tenant)
        val removed = before.policies.filter { after.policy(it.id) == null }.associateBy { it.id }
        if (removed.isEmpty()) {
            return
        }
        val live = after.bindings.filter { binding ->
            binding.policy in removed && binding !in changed && binding.expiresAt.let { it == null || snapshot.now < it }
        }
        for (binding in live) {
            val now = resolve(snapshot, after, binding)
            when {
                // It names nothing now: what it grants cannot be told (RC1-32).
                now == null -> into += GrantFragment.unresolved(change.tenant, binding)
                !samePolicy(removed.getValue(binding.policy), now) ->
                    now.statements.indices.forEach { split(change.tenant, now, it, Grantee.Bound(binding), into) }
            }
        }
        for (own in removed.values) {
            val shared = snapshot.platform.policy(own.id)?.takeIf { it.attachable }
            if (shared != null && !samePolicy(own, shared)) {
                shared.statements.indices.forEach { split(change.tenant, shared, it, Grantee.Attaching, into) }
            }
        }
    }

    /**
     * The ALLOWs of each policy of [after] that are new or widen what it had in [before] (§7.4.1, §7.4.4): what every
     * binding of that policy is granted anew, wherever it is. A policy that only narrows is not listed.
     */
    fun widened(before: GrantDocument, after: GrantDocument): Map<PolicyId, List<Int>> =
        after.policies.associate { it.id to granted(before.policy(it.id), it) }.filterValues { it.isNotEmpty() }

    /** The indexes of the ALLOWs of [policy] that narrow none of [previous]'s. */
    private fun granted(previous: GrantPolicy?, policy: GrantPolicy): List<Int> = policy.statements.indices.filter {
        val statement = policy.statements[it]
        statement.effect == Effect.ALLOW && (previous == null || !narrowsAny(policy, statement, previous))
    }

    /**
     * The fragments a new or changed [policy] grants: its ALLOWs that narrow none of [previous]'s, to every binding
     * that already points to it (the [changed] ones grant all of it anyway), and to attachments if it is attachable.
     */
    private fun changedPolicy(
        snapshot: GrantSnapshot,
        change: GrantChange,
        previous: GrantPolicy?,
        policy: GrantPolicy,
        changed: List<Binding>,
        into: MutableList<GrantFragment>,
    ) {
        val granted = granted(previous, policy)
        val bound = change.candidate.bindings.filter { binding ->
            binding.policy == policy.id && binding !in changed && binding.expiresAt.let { it == null || snapshot.now < it }
        }
        for (binding in bound) {
            granted.forEach { split(change.tenant, policy, it, Grantee.Bound(binding), into) }
        }
        if (policy.attachable) {
            val attached = if (previous?.attachable == true) granted else policy.statements.indices.toList()
            attached.forEach { split(change.tenant, policy, it, Grantee.Attaching, into) }
        }
    }

    /**
     * Whether [statement] (with [condition], its own and its policy's) narrows [previous] (with [previousCondition]):
     * the same effect, its actions and resources contained in the previous ones, and its condition implying the
     * previous one (§7.4.4). Then it matches no request the previous statement did not (A6).
     */
    fun narrows(
        statement: GrantStatement,
        condition: Condition?,
        previous: GrantStatement,
        previousCondition: Condition?,
    ): Boolean {
        if (statement.effect != previous.effect) {
            return false
        }
        if (!statement.actions.all { action ->
                previous.actions.any {
                    PatternContainment.contains(it, action) == Containment.YES
                }
            }
        ) {
            return false
        }
        val checked = condition?.let { check(it) ?: return false }
        return statement.resources.all { resource ->
            previous.resources.withIndex().any { (index, outer) ->
                val containment = PatternContainment.contains(outer, resource) as? ResourceContainment.Contained
                containment != null && implied(checked, previousCondition, previous.resources, index, resource) {
                    Specialization(checker, { null }, renames = containment.variables)
                }
            }
        }
    }

    /** A statement written as one of the previous ones, under the same policy condition, is unchanged. */
    private fun narrowsAny(policy: GrantPolicy, statement: GrantStatement, previous: GrantPolicy): Boolean {
        val condition = both(policy.condition, statement.condition)
        return previous.statements.any {
            val previousCondition = both(previous.condition, it.condition)
            (sameStatement(statement, it) && sameCondition(condition, previousCondition)) ||
                narrows(statement, condition, it, previousCondition)
        }
    }

    /** One fragment per action and resource of an ALLOW statement (§7.4.1). */
    private fun split(
        tenant: TenantId,
        policy: GrantPolicy,
        index: Int,
        grantee: Grantee,
        into: MutableList<GrantFragment>,
    ) {
        val statement = policy.statements[index]
        if (statement.effect != Effect.ALLOW) {
            return
        }
        val scope = when (grantee) {
            is Grantee.Bound -> ScopeSet.of(grantee.binding.scope.app, grantee.binding.scope.space)
            Grantee.Attaching -> ScopeSet.EVERY
        }
        val condition = both(policy.condition, statement.condition)
        for (action in statement.actions) {
            for (resource in statement.resources) {
                into += GrantFragment(tenant, policy.id, index, action, resource, condition, grantee, scope)
            }
        }
    }

    /** A binding's policy in the candidate: the tenant's own, the platform's, or its app's (RFC 0007 §3.1 N1). */
    private fun resolve(snapshot: GrantSnapshot, candidate: GrantDocument, binding: Binding): GrantPolicy? {
        candidate.policy(binding.policy)?.let { return it }
        snapshot.platform.policy(binding.policy)?.let { return it }
        val app = binding.scope.app
        return if (app is Match.Exactly) snapshot.apps[app.value]?.policy(binding.policy) else null
    }

    /** The verdict for one fragment (§7.4.3, the loop body). */
    fun verdict(grantor: Grantor, rights: GrantorRights, fragment: GrantFragment): GrantVerdict {
        undecidable(grantor, fragment)?.let { return it }
        val tenant = fragment.tenant
        val granted = fragment.condition?.let { condition ->
            when (val folded = Specialization(checker, tenantConstants(tenant)).fold(condition)) {
                Folded.True -> null
                Folded.False -> return GrantVerdict.Contained(null)
                is Folded.Residual -> check(folded.condition)
                    ?: return notCovered(Shortfall.CONDITION, "the fragment's condition does not check", null)

                is Folded.Incomparable -> return notCovered(Shortfall.CONDITION, folded.reason, null)
            }
        }
        val scope = refine(fragment.scope, granted)
        unreached(rights, scope)?.let { return it }
        val by = when (val found = holder(grantor, rights, fragment, scope, granted)) {
            null -> return notCovered(Shortfall.NO_ALLOW, "the grantor holds no ALLOW", null)
            is Search.Missed -> return notCovered(
                found.shortfall,
                "closest: ${describe(found.held.ref)}",
                found.held.ref
            )
            is Search.Found -> found.held
        }
        val deny = rights.denies.firstOrNull { it.applicability.intersects(scope) && !disjoint(it.statement, fragment) }
        if (deny != null) {
            return unknown(UnknownReason.DENY_OVERLAP, null, "may overlap ${describe(deny.ref)}", deny.ref)
        }
        return GrantVerdict.Contained(by.ref)
    }

    /** Why [fragment] is not analyzed against what [grantor] holds at all, or `null`. */
    private fun undecidable(grantor: Grantor, fragment: GrantFragment): GrantVerdict.Unknown? {
        if (!fragment.resolved) {
            return unknown(UnknownReason.UNRESOLVED, null, "[${fragment.policy.value}] does not resolve", null)
        }
        if (grantor.tenant != fragment.tenant) {
            return unknown(UnknownReason.CROSS_TENANT, null, "the grantor is in ${grantor.tenant.value}", null)
        }
        return null
    }

    /**
     * The verdict when [scope] (σ') settles it before any ALLOW is looked at, or `null`: contained when the fragment
     * applies nowhere, UNKNOWN when it reaches an app whose document could not be read (RC1-4).
     */
    private fun unreached(rights: GrantorRights, scope: ScopeSet): GrantVerdict? = when {
        scope.isEmpty -> GrantVerdict.Contained(null)
        rights.unread(
            scope
        ) -> unknown(UnknownReason.UNREADABLE, null, "an app document it reaches could not be read", null)
        else -> null
    }

    /** What the search for one containing ALLOW found: it, the one that got furthest, or nothing (`null`). */
    private sealed interface Search {
        class Found(val held: HeldStatement) : Search

        class Missed(val shortfall: Shortfall, val held: HeldStatement) : Search
    }

    /** The first ALLOW that contains [fragment] in [scope] (§7.4.3, "only one H"). */
    private fun holder(
        grantor: Grantor,
        rights: GrantorRights,
        fragment: GrantFragment,
        scope: ScopeSet,
        granted: CheckedCondition?,
    ): Search? {
        var closest: Search.Missed? = null
        for (held in rights.allows) {
            val shortfall = when {
                !scope.coveredBy(held.applicability) -> Shortfall.SCOPE
                held.statement.actions.none { PatternContainment.contains(it, fragment.action) == Containment.YES } ->
                    Shortfall.ACTION

                resourceAndCondition(grantor, scope, held, fragment, granted) ->
                    if (outlives(held, fragment)) return Search.Found(held) else Shortfall.EXPIRY

                held.statement.resources.any { contained(it, fragment.resource) } -> Shortfall.CONDITION
                else -> Shortfall.RESOURCE
            }
            if (closest == null || closest.shortfall < shortfall) {
                closest = Search.Missed(shortfall, held)
            }
        }
        return closest
    }

    /**
     * Whether the grantor holds [held] for as long as [fragment] is granted (RC1-3): through a binding without
     * `expiresAt`, or one that expires no earlier than the fragment's. An attachment never expires.
     */
    private fun outlives(held: HeldStatement, fragment: GrantFragment): Boolean {
        val until = held.expiresAt ?: return true
        val granted = fragment.expiresAt ?: return false
        return granted <= until
    }

    private fun contained(outer: ResourcePattern, inner: ResourcePattern): Boolean =
        PatternContainment.contains(outer, inner) is ResourceContainment.Contained

    /** Whether one resource of [held] contains the fragment's and its condition then holds wherever the fragment's does. */
    private fun resourceAndCondition(
        grantor: Grantor,
        scope: ScopeSet,
        held: HeldStatement,
        fragment: GrantFragment,
        granted: CheckedCondition?,
    ): Boolean {
        val resources = held.statement.resources
        return resources.withIndex().any { (index, outer) ->
            val containment = PatternContainment.contains(outer, fragment.resource) as? ResourceContainment.Contained
            containment != null && implied(granted, held.condition, resources, index, fragment.resource) {
                Specialization(
                    checker,
                    grantorConstants(grantor, fragment.tenant, scope),
                    opaque = { it.kind in SUBJECT_KINDS },
                    renames = containment.variables,
                )
            }
        }
    }

    /**
     * Whether [outer] (the condition of a statement whose resource [index] of [resources] contains [inner]),
     * specialized, holds on every request that matches [inner] and satisfies [inner condition][condition].
     */
    private fun implied(
        condition: CheckedCondition?,
        outer: Condition?,
        resources: List<ResourcePattern>,
        index: Int,
        inner: ResourcePattern,
        specialization: () -> Specialization,
    ): Boolean {
        if (outer == null) {
            return true
        }
        // The variables of a statement come from the resource that matched: only one may match.
        if (readsVariables(outer) && resources.indices.any {
                it != index && !PatternContainment.disjoint(resources[it], inner)
            }
        ) {
            return false
        }
        val specialized = specialization()
        return when (val folded = specialized.fold(outer)) {
            Folded.True -> true
            Folded.False, is Folded.Incomparable -> false
            is Folded.Residual -> {
                val checked = specialized.check(folded.condition) ?: return false
                condition != null && ConditionContainment.implies(condition, checked) == Containment.YES
            }
        }
    }

    /** A DENY is disjoint from a fragment when none of its actions, or none of its resources, can match the fragment's. */
    private fun disjoint(deny: GrantStatement, fragment: GrantFragment): Boolean =
        deny.actions.all { PatternContainment.disjoint(it, fragment.action) } ||
            deny.resources.all { PatternContainment.disjoint(it, fragment.resource) }

    private fun check(condition: Condition): CheckedCondition? =
        (checker.check(condition) as? CheckResult.Valid)?.condition

    private fun notCovered(shortfall: Shortfall, detail: String, closest: StatementRef.Policy?) =
        unknown(UnknownReason.NOT_COVERED, shortfall, detail, closest)

    private fun unknown(reason: UnknownReason, shortfall: Shortfall?, detail: String, closest: StatementRef.Policy?) =
        GrantVerdict.Unknown(reason, shortfall, detail, closest)

    private fun describe(ref: StatementRef.Policy): String = "${ref.policy.value}[${ref.index}] via ${ref.via}"

    /** The entries of [before] marked `protected` that [after] changes or removes, or unmarks. */
    private fun unprotect(before: GrantDocument, after: GrantDocument): List<ProtectedEntry> {
        val entries = ArrayList<ProtectedEntry>()
        for (policy in before.policies) {
            if (policy.protected && after.policy(policy.id)?.let { samePolicy(policy, it) } != true) {
                entries += ProtectedEntry.Policy(policy.id)
            }
        }
        for (binding in before.bindings) {
            if (binding.id !in before.protectedBindings) continue
            val kept = after.bindings.any { same(it, binding) } && binding.id in after.protectedBindings
            if (!kept) entries += ProtectedEntry.Binding(binding.id)
        }
        return entries
    }

    private fun samePolicy(a: GrantPolicy, b: GrantPolicy): Boolean =
        a.attachable == b.attachable && a.protected == b.protected && sameCondition(a.condition, b.condition) &&
            a.statements.size == b.statements.size &&
            a.statements.indices.all { sameStatement(a.statements[it], b.statements[it]) }

    private fun sameStatement(a: GrantStatement, b: GrantStatement): Boolean =
        a.effect == b.effect && a.name == b.name && a.actions == b.actions && a.resources == b.resources &&
            a.onMissing == b.onMissing && sameCondition(a.condition, b.condition)

    /**
     * Conditions compare by their canonical rendering; one that does not check (an attribute outside the catalog)
     * only by how it is written.
     */
    private fun sameCondition(a: Condition?, b: Condition?): Boolean {
        if (a == null || b == null) {
            return a == null && b == null
        }
        val x = check(a)
        val y = check(b)
        return if (x != null && y != null) x == y else written(a) == written(b)
    }

    private companion object {
        val SCOPE_APP: Attribute = Attribute.of(Kind.SCOPE_APP)
        val SCOPE_SPACE: Attribute = Attribute.of(Kind.SCOPE_SPACE)

        fun same(a: Binding, b: Binding): Boolean =
            a.id == b.id && a.subject == b.subject && a.policy == b.policy && a.scope.app == b.scope.app &&
                a.scope.space == b.scope.space && a.expiresAt == b.expiresAt && a.breakGlass == b.breakGlass

        /** `subject.tenant` and `scope.tenant` are the document's tenant for grantor and grantee alike. */
        fun tenantConstants(tenant: TenantId): (Attribute) -> Constant? = { attribute ->
            when (attribute.kind) {
                Kind.SUBJECT_TENANT, Kind.SCOPE_TENANT -> Constant.Str(tenant.value)
                else -> null
            }
        }

        /** The grantor's own attributes (§7.4.3 `specialize`). `scope.roles` only where it has one value. */
        fun grantorConstants(grantor: Grantor, tenant: TenantId, scope: ScopeSet): (Attribute) -> Constant? {
            val roles = grantor.memberships.roles
            val scopeRoles = (scope.spaces as? Dimension.Only)?.values
                ?.map { grantor.roles(it) }?.distinct()?.singleOrNull()
            return { attribute ->
                when (attribute.kind) {
                    Kind.SUBJECT_ID -> Constant.Str(grantor.id.value)
                    Kind.SUBJECT_TENANT, Kind.SCOPE_TENANT -> Constant.Str(tenant.value)
                    Kind.SUBJECT_AUTHENTICATED -> Constant.Bool(true)
                    Kind.SUBJECT_ROLES -> Constant.Strings(
                        roles.values.flatMapTo(LinkedHashSet()) { set -> set.map { it.value } }
                    )
                    Kind.SUBJECT_SCOPED_ROLES -> Constant.Strings(
                        roles.flatMapTo(LinkedHashSet()) { (space, set) ->
                            set.map { if (space == SpaceId.DEFAULT) it.value else "${it.value}@${space.value}" }
                        }
                    )

                    // Exactly as the engine reads it: a value of any type is its toString() (RC1-2).
                    Kind.SUBJECT_ATTRIBUTE -> Constant.Str(SubjectAttributes.string(grantor.attributes, attribute.name))
                    Kind.SCOPE_ROLES -> scopeRoles?.let { Constant.Strings(it) }
                    else -> null
                }
            }
        }

        /** σ' (§7.4.3): the fragment's scope narrowed by what its condition requires of `scope.app` and `scope.space`. */
        fun refine(scope: ScopeSet, condition: CheckedCondition?): ScopeSet {
            if (condition == null) {
                return scope
            }
            val apps = keys(condition, SCOPE_APP)?.let { values -> Dimension.Only(values.mapTo(HashSet(), ::AppId)) }
            val spaces = keys(
                condition,
                SCOPE_SPACE
            )?.let { values -> Dimension.Only(values.mapTo(HashSet(), ::SpaceId)) }
            return ScopeSet(
                apps?.let { ScopeSet.intersect(scope.apps, it) } ?: scope.apps,
                spaces?.let { ScopeSet.intersect(scope.spaces, it) } ?: scope.spaces,
            )
        }

        /** The exact keys, without the wildcard no id can be; `null` when unconstrained or folded. */
        fun keys(condition: CheckedCondition, attribute: Attribute): List<String>? {
            val keys = IndexKeys.of(condition, attribute) as? IndexKeys.Only ?: return null
            return if (keys.folded) null else keys.values.filter { it != Match.WILDCARD }
        }

        fun readsVariables(condition: Condition): Boolean = when (condition) {
            is Condition.And -> condition.conditions.any(::readsVariables)
            is Condition.Or -> condition.conditions.any(::readsVariables)
            is Condition.Not -> readsVariables(condition.condition)
            is Condition.Eq -> variable(condition.attribute) ||
                (condition.value as? Operand.Attr)?.let { variable(it.path) } == true

            is Condition.In -> variable(condition.attribute)
            is Condition.Contains -> variable(condition.attribute)
            is Condition.Prefix -> variable(condition.attribute)
            is Condition.Cidr -> variable(condition.attribute)
            is Condition.Exists -> variable(condition.attribute)
            is Condition.Time -> false
        }

        fun variable(path: String): Boolean = path.startsWith(Kind.RESOURCE_VAR.path)

        /** A rendering of [condition] as written: equal renderings are the same condition. */
        fun written(condition: Condition): String = when (condition) {
            is Condition.And -> condition.conditions.joinToString(",", "and(", ")", transform = ::written)
            is Condition.Or -> condition.conditions.joinToString(",", "or(", ")", transform = ::written)
            is Condition.Not -> "not(${written(condition.condition)})"
            is Condition.Eq -> "eq(${condition.attribute},${operand(condition.value)},${condition.ignoreCase})"
            is Condition.In -> "in(${condition.attribute},${condition.values.map(::operand)})"
            is Condition.Contains -> "contains(${operand(condition.value)},${condition.attribute})"
            is Condition.Prefix -> "prefix(${condition.attribute},${operand(condition.value)},${condition.ignoreCase})"
            is Condition.Cidr -> "cidr(${condition.attribute},${condition.blocks.map(::operand)})"
            is Condition.Exists -> "exists(${condition.attribute})"
            is Condition.Time -> with(condition) { "time($notBefore,$notAfter,$zone,$daysOfWeek,$from,$to)" }
        }

        fun operand(operand: Operand): String = when (operand) {
            is Operand.Attr -> "attr(${operand.path})"
            is Literal.Str -> "str(${operand.value})"
            is Literal.Bool -> "bool(${operand.value})"
        }
    }
}
