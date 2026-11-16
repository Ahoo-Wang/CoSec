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
import me.ahoo.cosec.engine.pattern.PatternContainment
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.ConditionContainment
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.model.condition.ZoneResolver

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
 *    resource to capture it at the same position: a statement holds under any one of its matching resources'
 *    captures (`ALG-10`), so the one that contains the fragment's suffices; one that reads an external attribute is
 *    never comparable;
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
@InternalCoSecApi
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
    private val equality = GrantEquality(checker)
    private val narrowing = GrantNarrowing(checker, equality)
    private val fragmenter = GrantFragmenter(narrowing, equality)
    private val verdicts = GrantVerdicts(checker, equality)
    private val entries = ProtectedEntries(equality)

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
        val unprotect = entries.unprotect(snapshot.tenant(change.tenant), change.candidate)
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
    fun fragments(snapshot: GrantSnapshot, change: GrantChange): List<GrantFragment> =
        fragmenter.fragments(snapshot, change)

    /**
     * The ALLOWs of each policy of [after] that are new or widen what it had in [before] (§7.4.1, §7.4.4): what every
     * binding of that policy is granted anew, wherever it is. A policy that only narrows is not listed.
     */
    fun widened(before: GrantDocument, after: GrantDocument): Map<PolicyId, List<Int>> =
        fragmenter.widened(before, after)

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
    ): Boolean = narrowing.narrows(statement, condition, previous, previousCondition)

    /** The verdict for one fragment (§7.4.3, the loop body). */
    fun verdict(grantor: Grantor, rights: GrantorRights, fragment: GrantFragment): GrantVerdict =
        verdicts.verdict(grantor, rights, fragment)
}
