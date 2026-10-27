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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.engine.GuardBehavior
import me.ahoo.cosec.engine.RuleBehavior
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/** Platform boundaries (RFC 0007 §7.6) case by case; `FlatEnginePropertyTest` checks P-B1–P-B3. */
class BoundaryTest {
    private val fixture = FlatFixture()
    private val allowAll = fixture.policy("all", allow())

    private fun platform(vararg boundaries: CompiledPolicy, default: String? = null) =
        CompiledDocument.platform(listOf(allowAll), listOf(anyone("g", "all")), boundaries.toList(), default)

    private fun decide(platform: CompiledDocument, tenant: CompiledDocument = tenant(), request: FlatRequest = flatRequest()): Decision {
        val view = SnapshotView(platform, null, tenant)
        val decisions = TraceLevel.entries.map { CoSecFlatEngine.evaluate(request, view, it).decision }
        decisions.distinct().assert().describedAs("$decisions").hasSize(1)
        return decisions.first()
    }

    private val permit = Decision.Permit(ref("all", 0, bound("g")))

    @Test
    fun `without a boundary a permit stays a permit`() {
        decide(platform()).assert().isEqualTo(permit)
    }

    @Test
    fun `a permit within the default boundary stays a permit`() {
        val boundary = fixture.policy("b", allow(RuleBehavior.CONDITION_MISS), allow())
        decide(platform(boundary, default = "b")).assert().isEqualTo(permit)
    }

    @Test
    fun `a permit outside the boundary is a deny by the boundary`() {
        val boundary = fixture.policy("b", allow(RuleBehavior.CONDITION_MISS), allow(RuleBehavior.ACTION_MISS))
        decide(platform(boundary, default = "b"))
            .assert().isEqualTo(Decision.Deny(StatementRef.Boundary(PolicyId("b"), TenantId("acme"))))
        // An empty boundary holds nothing in.
        decide(platform(fixture.policy("empty"), default = "empty"))
            .assert().isInstanceOf(Decision.Deny::class.java)
    }

    @Test
    fun `a failing boundary statement without a match is indeterminate, a match still permits`() {
        val failing = fixture.policy("b", allow(RuleBehavior.THROW), allow(RuleBehavior.CONDITION_MISS))
        val decision = decide(platform(failing, default = "b")) as Decision.Indeterminate
        val cause = decision.cause as Cause.BoundaryUnavailable
        cause.boundary.assert().isEqualTo(PolicyId("b"))
        cause.error.assert().isSameAs(fixture.error(failing, "b.0"))
        val rescued = fixture.policy("c", allow(RuleBehavior.THROW), allow())
        decide(platform(rescued, default = "c")).assert().isEqualTo(permit)
        val guarded = fixture.policy("d", allow(), guard = fixture.guard("gd", GuardBehavior.THROW))
        (decide(platform(guarded, default = "d")) as Decision.Indeterminate).cause
            .assert().isInstanceOf(Cause.BoundaryUnavailable::class.java)
        val missed = fixture.policy("e", allow(), guard = fixture.guard("ge", GuardBehavior.MISS))
        decide(platform(missed, default = "e")).assert().isInstanceOf(Decision.Deny::class.java)
    }

    @Test
    fun `a tenant's own boundary replaces the default, and one the platform lacks fails closed`() {
        val narrow = fixture.policy("narrow", allow(RuleBehavior.CONDITION_MISS))
        val wide = fixture.policy("wide", allow())
        val platform = platform(narrow, wide, default = "narrow")
        decide(platform).assert().isInstanceOf(Decision.Deny::class.java)
        decide(
            platform,
            CompiledDocument.tenant(TenantId("acme"), emptyList(), emptyList(), "wide")
        ).assert().isEqualTo(permit)
        decide(platform, CompiledDocument.tenant(TenantId("acme"), emptyList(), emptyList(), "gone"))
            .assert().isEqualTo(Decision.Indeterminate(Cause.MissingPolicies(setOf(PolicyId("gone")))))
    }

    @Test
    fun `the platform tenant has no boundary`() {
        val closed = fixture.policy("closed", allow(RuleBehavior.CONDITION_MISS))
        val platformTenant = flatRequest(tenant = TenantId.PLATFORM.value)
        decide(platform(closed, default = "closed"), tenant(tenant = TenantId.PLATFORM.value), platformTenant)
            .assert().isEqualTo(permit)
        assertThrownBy<IllegalArgumentException> {
            CompiledDocument.tenant(TenantId.PLATFORM, emptyList(), emptyList(), "closed")
        }
    }

    @Test
    fun `a boundary is never consulted for a deny`() {
        val closed = fixture.policy("closed", allow(RuleBehavior.THROW))
        val platform = CompiledDocument.platform(
            listOf(fixture.policy("d", deny())),
            listOf(anyone("g", "d")),
            listOf(closed),
            "closed",
        )
        decide(platform).assert().isEqualTo(Decision.Deny(ref("d", 0, bound("g"))))
        decide(
            CompiledDocument.platform(emptyList(), emptyList(), listOf(closed), "closed")
        ).assert().isEqualTo(Decision.Deny(null))
    }

    @Test
    fun `the trace lists the boundary statements it evaluated`() {
        val boundary = fixture.policy("b", allow(RuleBehavior.CONDITION_MISS), allow(), allow())
        val view = SnapshotView(platform(boundary, default = "b"), null, tenant())
        val trace = CoSecFlatEngine.evaluate(flatRequest(), view, TraceLevel.FULL).trace!!
        trace.boundary.map { it.outcome }.assert().containsExactly(
            CandidateOutcome.CONDITION_MISS,
            CandidateOutcome.MATCHED
        )
        trace.boundary.first().via.assert().isEqualTo(BindingRef.Boundary(TenantId("acme")))
        CoSecFlatEngine.evaluate(flatRequest(), SnapshotView(platform(), null, tenant()), TraceLevel.FULL).trace!!
            .boundary.assert().isEmpty()
    }

    @Test
    fun `a boundary only allows, is never bound or attached, and the default is one`() {
        assertThrownBy<IllegalArgumentException> {
            CompiledDocument.platform(emptyList(), emptyList(), listOf(fixture.policy("b", deny())))
        }.hasMessageContaining("only allows")
        assertThrownBy<IllegalArgumentException> {
            CompiledDocument.platform(emptyList(), emptyList(), listOf(fixture.policy("b", allow(), attachable = true)))
        }.hasMessageContaining("cannot be attached")
        assertThrownBy<IllegalArgumentException> {
            CompiledDocument.platform(emptyList(), emptyList(), listOf(fixture.policy("b", *Array(65) { allow() })))
        }.hasMessageContaining("more than 64")
        assertThrownBy<IllegalArgumentException> {
            CompiledDocument.platform(emptyList(), listOf(anyone("g", "b")), listOf(fixture.policy("b", allow())))
        }.hasMessageContaining("binds boundary policy b")
        assertThrownBy<IllegalArgumentException> {
            CompiledDocument.platform(listOf(allowAll), emptyList(), emptyList(), "all")
        }.hasMessageContaining("is not a boundary policy")
        assertThrownBy<IllegalArgumentException> {
            CompiledDocument.platform(listOf(allowAll), emptyList(), listOf(fixture.policy("all", allow())))
        }.hasMessageContaining("Duplicate policy id")
        assertThrownBy<IllegalArgumentException> {
            CompiledDocument.platform(
                emptyList(),
                emptyList(),
                listOf(fixture.policy(BuiltinPolicies.ROOT.value, allow()))
            )
        }
    }

    @Test
    fun `a boundary cannot be attached through a principal's memberships`() {
        val boundary = fixture.policy("b", allow())
        val platform = CompiledDocument.platform(emptyList(), emptyList(), listOf(boundary))
        decide(platform, request = flatRequest(attachments = listOf("b")))
            .assert().isEqualTo(Decision.Indeterminate(Cause.MissingPolicies(setOf(PolicyId("b")))))
    }
}
