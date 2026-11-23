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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Escalation
import me.ahoo.cosec.control.grant.GrantAnalysis
import me.ahoo.cosec.control.grant.GrantFragment
import me.ahoo.cosec.control.grant.GrantVerdict
import me.ahoo.cosec.control.grant.UnknownReason
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
 * is one; so are all the fragments past the comparison budget. Every other unknown fragment is an escalation of its own.
 */
class UnknownEscalationsTest {
    private val acme = TenantId("acme")
    private val key = SnapshotKey.Tenant("acme")

    private fun fragment(id: String) = GrantFragment.unresolved(
        acme,
        Binding(BindingId(id), SubjectRef.Role(acme, RoleId("r")), BindingScope(Match.Any, Match.Any), PolicyId("p")),
    )

    private fun unknown(reason: UnknownReason) = GrantVerdict.Unknown(reason, null, "", null)

    @Test
    fun `a change too large to split is one escalation`() {
        val escalations = UnknownEscalations.of(key, GrantAnalysis(emptyList(), emptyList(), tooLarge = true))
        escalations.map { it.reason }.assert().containsExactly(Escalation.Reason.TOO_LARGE)
        escalations.single().subject.assert().isEqualTo("change of $key")
    }

    @Test
    fun `every fragment past the comparison budget is one escalation, counted`() {
        val verdicts = (1..1_000).map { fragment("b$it") to unknown(UnknownReason.TOO_LARGE) } +
            (fragment("held") to unknown(UnknownReason.NOT_COVERED)) +
            (fragment("contained") to GrantVerdict.Contained(null))
        val escalations = UnknownEscalations.of(key, GrantAnalysis(verdicts, emptyList()))
        escalations.map { it.reason }.assert().containsExactly(
            Escalation.Reason.NOT_COVERED,
            Escalation.Reason.TOO_LARGE
        )
        escalations.last().subject.assert().isEqualTo("1000 fragments of $key")
    }

    @Test
    fun `nothing unknown is no escalation`() {
        UnknownEscalations.of(key, GrantAnalysis(listOf(fragment("b") to GrantVerdict.Contained(null)), emptyList()))
            .assert().isEmpty()
    }
}
