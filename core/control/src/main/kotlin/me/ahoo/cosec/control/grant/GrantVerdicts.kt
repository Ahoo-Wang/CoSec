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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.SubjectAttributes
import me.ahoo.cosec.engine.pattern.PatternContainment
import me.ahoo.cosec.engine.pattern.ResourceContainment
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Attribute.Kind
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.ConditionContainment
import me.ahoo.cosec.model.condition.Containment
import me.ahoo.cosec.model.condition.IndexKeys
import me.ahoo.cosec.model.decision.StatementRef

/** The verdict for each fragment (§7.4.3): whether one single ALLOW of the grantor contains it, or what stopped the search. */
internal class GrantVerdicts(private val checker: ConditionChecker, private val equality: GrantEquality) {
    /** The verdict for one fragment (§7.4.3, the loop body). */
    fun verdict(grantor: Grantor, rights: GrantorRights, fragment: GrantFragment): GrantVerdict {
        undecidable(grantor, fragment)?.let { return it }
        val tenant = fragment.tenant
        val granted = fragment.condition?.let { condition ->
            when (val folded = Specialization(checker, tenantConstants(tenant)).fold(condition)) {
                Folded.True -> null
                Folded.False -> return GrantVerdict.Contained(null)
                is Folded.Residual -> equality.check(folded.condition)
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
        val grantee = fragment.grantee
        if (fragment.tenant == TenantId.DEFAULT && grantee is Grantee.Bound && grantee.binding.subject == SubjectRef.Anyone) {
            return unknown(
                UnknownReason.ANONYMOUS,
                null,
                "anyone in ${TenantId.DEFAULT.value} includes unauthenticated callers",
                null
            )
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
        return held.statement.resources.any { outer ->
            val containment = PatternContainment.contains(outer, fragment.resource) as? ResourceContainment.Contained
            containment != null && GrantImplication.implied(granted, held.condition) {
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
     * Whether [outer] (the condition of a statement one of whose resources contains the inner one), specialized, holds
     * on every request that matches the inner resource and satisfies [condition].
     */
    private fun implied(
        condition: CheckedCondition?,
        outer: Condition?,
        specialization: () -> Specialization,
    ): Boolean {
        if (outer == null) {
            return true
        }
        // A statement holds under any of its matching resources' captures (ALG-10): resource [index] contains [inner],
        // so it matches every request [inner] does, and the condition read under its captures is enough.
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

    private fun notCovered(shortfall: Shortfall, detail: String, closest: StatementRef.Policy?) =
        unknown(UnknownReason.NOT_COVERED, shortfall, detail, closest)

    private fun unknown(reason: UnknownReason, shortfall: Shortfall?, detail: String, closest: StatementRef.Policy?) =
        GrantVerdict.Unknown(reason, shortfall, detail, closest)

    private fun describe(ref: StatementRef.Policy): String = "${ref.policy.value}[${ref.index}] via ${ref.via}"

    private companion object {
        val SCOPE_APP: Attribute = Attribute.of(Kind.SCOPE_APP)
        val SCOPE_SPACE: Attribute = Attribute.of(Kind.SCOPE_SPACE)

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
    }
}
