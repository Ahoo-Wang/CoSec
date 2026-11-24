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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Escalation
import me.ahoo.cosec.control.grant.GrantAnalysis
import me.ahoo.cosec.control.grant.GrantFragment
import me.ahoo.cosec.control.grant.GrantVerdict
import me.ahoo.cosec.control.grant.Grantee
import me.ahoo.cosec.control.grant.ScopeSet
import me.ahoo.cosec.control.grant.UnknownReason
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * CTL-49, DR1-E-1: what is too large to analyse is one escalation, never one per fragment. A change too large to split
 * is one; so are all the fragments past the comparison budget.
 *
 * CTL-88, DR2-E-1: the other unknown fragments are one escalation per statement and reason, counted and digested, at
 * most [UnknownEscalations.MAX_GROUPS] of them and one for the rest, so what a change records is bounded whatever its
 * shape, and an approval still covers exactly the fragments it was shown.
 */
class UnknownEscalationsTest {
    private val acme = TenantId("acme")
    private val key = SnapshotKey.Tenant("acme")

    private fun binding(id: String, policy: String = "p") =
        Binding(BindingId(id), SubjectRef.Role(acme, RoleId("r")), BindingScope(Match.Any, Match.Any), PolicyId(policy))

    private fun fragment(id: String) = GrantFragment.unresolved(acme, binding(id))

    /** One fragment of [policy]'s statement [statement]: action `[action]`, resource `[resource]`, to binding `b`. */
    private fun granted(policy: String, statement: Int, action: String, resource: String = "order/**") = GrantFragment(
        acme,
        PolicyId(policy),
        statement,
        ActionPattern.parse(action),
        ResourcePattern.parse(resource),
        null,
        Grantee.Bound(binding("b", policy)),
        ScopeSet.of(Match.Any, Match.Any),
    )

    private fun unknown(reason: UnknownReason) = GrantVerdict.Unknown(reason, null, "", null)

    private fun notCovered(vararg fragments: GrantFragment) =
        GrantAnalysis(fragments.map { it to unknown(UnknownReason.NOT_COVERED) })

    private fun subjects(analysis: GrantAnalysis) = UnknownEscalations.of(key, analysis).map { it.subject }.toSet()

    @Test
    fun `a change too large to split is one escalation`() {
        val escalations = UnknownEscalations.of(key, GrantAnalysis(emptyList(), tooLarge = true))
        escalations.map { it.reason }.assert().containsExactly(Escalation.Reason.TOO_LARGE)
        escalations.single().subject.assert().isEqualTo("change of $key")
    }

    @Test
    fun `every fragment past the comparison budget is one escalation, counted`() {
        val verdicts = (1..1_000).map { fragment("b$it") to unknown(UnknownReason.TOO_LARGE) } +
            (fragment("held") to unknown(UnknownReason.NOT_COVERED)) +
            (fragment("contained") to GrantVerdict.Contained(null))
        val escalations = UnknownEscalations.of(key, GrantAnalysis(verdicts))
        escalations.map { it.reason }.assert().containsExactly(
            Escalation.Reason.NOT_COVERED,
            Escalation.Reason.TOO_LARGE
        )
        escalations.last().subject.assert().startsWith("1000 fragments of $key ")
    }

    @Test
    fun `nothing unknown is no escalation`() {
        UnknownEscalations.of(key, GrantAnalysis(listOf(fragment("b") to GrantVerdict.Contained(null))))
            .assert().isEmpty()
    }

    @Test
    fun `the unknown fragments of one statement are one escalation, counted, naming the first few - CTL-88`() {
        val fragments = (1..1_000).map { granted("wide", 0, "order:a$it") }
        val escalation = UnknownEscalations.of(key, notCovered(*fragments.toTypedArray())).single()
        escalation.reason.assert().isEqualTo(Escalation.Reason.NOT_COVERED)
        escalation.subject.assert().startsWith("wide[0] NOT_COVERED: 1000 fragments ")
        escalation.detail.assert().contains("order:a1 on order/**").contains("order:a3 on").doesNotContain("order:a4 ")
            .endsWith("; and 997 more")
    }

    @Test
    fun `each statement and each reason is an escalation of its own - CTL-88`() {
        val analysis = GrantAnalysis(
            listOf(
                granted("p", 0, "order:read") to unknown(UnknownReason.NOT_COVERED),
                granted("p", 0, "order:write") to unknown(UnknownReason.NOT_COVERED),
                granted("p", 0, "order:delete") to unknown(UnknownReason.DENY_OVERLAP),
                granted("p", 1, "order:read") to unknown(UnknownReason.NOT_COVERED),
                granted("q", 0, "order:read") to unknown(UnknownReason.NOT_COVERED),
                fragment("dangling") to unknown(UnknownReason.UNRESOLVED),
            ),
        )
        UnknownEscalations.of(key, analysis).map { it.subject.substringBefore(" fragment") }.assert().containsExactly(
            "p[0] NOT_COVERED: 2",
            "p[0] DENY_OVERLAP: 1",
            "p[1] NOT_COVERED: 1",
            "q[0] NOT_COVERED: 1",
            "p (does not resolve) UNRESOLVED: 1",
        )
    }

    @Test
    fun `past the group bound the rest is one TOO_LARGE escalation, which needs an approval - CTL-88`() {
        val groups = UnknownEscalations.MAX_GROUPS + 50
        val fragments = (0 until groups).flatMap { statement -> (1..2).map { granted("p", statement, "order:a$it") } }
        val escalations = UnknownEscalations.of(key, notCovered(*fragments.toTypedArray()))
        escalations.size.assert().isEqualTo(UnknownEscalations.MAX_GROUPS + 1)
        val rest = escalations.last()
        rest.reason.assert().isEqualTo(Escalation.Reason.TOO_LARGE)
        rest.subject.assert().startsWith("100 more fragments of $key ")
        // What is left out is not analysed: never informational, and never what the role class lifts (CTL-79).
        rest.informational.assert().isFalse()
        RoleClass.LIFTED.assert().doesNotContain(Escalation.Reason.TOO_LARGE)
    }

    @Test
    fun `a change needs an approval exactly when it did before the grouping - CTL-88`() {
        val shapes = listOf(
            notCovered(),
            notCovered(granted("p", 0, "order:read")),
            notCovered(*(0..UnknownEscalations.MAX_GROUPS).map { granted("p", it, "order:read") }.toTypedArray()),
            GrantAnalysis(listOf(granted("p", 0, "order:read") to GrantVerdict.Contained(null))),
            GrantAnalysis(listOf(granted("p", 0, "order:read") to unknown(UnknownReason.TOO_LARGE))),
            GrantAnalysis(emptyList(), tooLarge = true),
        )
        shapes.map { analysis -> UnknownEscalations.of(key, analysis).any { !it.informational } }
            .assert().isEqualTo(shapes.map { it.needsApproval })
    }

    @Test
    fun `a fragment more, fewer or another is a subject an approval of the others does not cover - CTL-88`() {
        val a = granted("p", 0, "order:a")
        val b = granted("p", 0, "order:b")
        val c = granted("p", 0, "order:c")
        val approved = subjects(notCovered(a, b))
        // The same analysis is the same subjects: an approval it was given for still covers it.
        subjects(notCovered(a, b)).assert().isEqualTo(approved)
        listOf(notCovered(a, b, c), notCovered(a), notCovered(a, c)).forEach { now ->
            approved.containsAll(subjects(now)).assert().isFalse()
        }
        // So is the rest past the group bound.
        val listed = (0 until UnknownEscalations.MAX_GROUPS).map { granted("p", it, "order:a") }
        val restApproved = subjects(notCovered(*(listed + granted("q", 0, "order:a")).toTypedArray()))
        approved.size.assert().isEqualTo(1)
        restApproved.containsAll(subjects(notCovered(*(listed + granted("q", 0, "order:b")).toTypedArray())))
            .assert().isFalse()
    }

    @Test
    fun `what the escalations hold is bounded whatever the fragments - CTL-88`() {
        val long = "order/" + "x".repeat(2_000)
        val policy = "p".repeat(1_000)
        val fragments = (0 until UnknownEscalations.MAX_GROUPS * 2).flatMap { statement ->
            (1..10).map { granted(policy, statement, "order:a$it", "$long/$it") }
        }
        val escalations = UnknownEscalations.of(key, notCovered(*fragments.toTypedArray()))
        escalations.size.assert().isEqualTo(UnknownEscalations.MAX_GROUPS + 1)
        escalations.maxOf { it.subject.length }.assert().isLessThanOrEqualTo(MAX_SUBJECT)
        escalations.maxOf { it.detail.length }.assert().isLessThanOrEqualTo(MAX_DETAIL)
    }

    companion object {
        /** A policy id clipped, a statement index, a reason, a count and the digest. */
        const val MAX_SUBJECT = UnknownEscalations.MAX_TEXT + 160

        /** The examples, each clipped, and how many more. */
        const val MAX_DETAIL = UnknownEscalations.EXAMPLES * (UnknownEscalations.MAX_TEXT + 40) + 40
    }
}
