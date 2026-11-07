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

import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * #1208 (`ATR-8`): the attribute plan holds the external attributes of the boundary that holds the request, its
 * statements' and its policy condition's, so a boundary that reads one is decided on what the provider answers.
 */
class BoundaryAttributePlanTest {
    private val inFence = Attribute.external("ext.geo.inFence", AttributeType.BOOL)
    private val country = Attribute.external("ext.geo.country", AttributeType.STRING)
    private val compiler = PolicyCompiler(ConditionChecker(catalog = AttributeCatalog.of(listOf(inFence, country))))

    /** Anyone may do anything: no policy reads an external attribute, only the boundary does. */
    private val allowAll = compiler.compile(
        PolicyId("all"),
        null,
        listOf(StatementSource("s", Effect.ALLOW, listOf("*")))
    )
    private val permit = Decision.Permit(StatementRef.Policy(PolicyId("all"), 0, "s", bound("g")))

    private fun boundary(
        id: String,
        condition: Condition?,
        action: String = "*",
        guard: Condition? = null,
        onMissing: OnMissing? = null,
    ) = compiler.compile(
        PolicyId(id),
        guard,
        listOf(StatementSource("s", Effect.ALLOW, listOf(action), condition = condition, onMissing = onMissing)),
    )

    private fun inFence(value: Boolean) = Condition.Eq(inFence.path, Literal.Bool(value))

    private fun platform(vararg boundaries: CompiledPolicy, default: String?) =
        CompiledDocument.platform(
            listOf(allowAll),
            listOf(anyone("g", "all")),
            emptyList(),
            boundaries.toList(),
            default
        )

    private fun view(platform: CompiledDocument, tenant: CompiledDocument = tenant()) = SnapshotView(
        platform,
        null,
        tenant
    )

    private fun plan(view: SnapshotView, request: FlatRequest = flatRequest()): List<String> =
        CoSecFlatEngine.plan(request, view).attributes.map { it.path }

    /** What every trace level decides on [values], all of them available; they agree (P2). */
    private fun decide(view: SnapshotView, values: Map<String, Any>, available: Set<String> = values.keys): Decision {
        val base = flatRequest()
        val request = FlatRequest(
            base.request,
            base.scope,
            base.memberships,
            base.time,
            base.attributes,
            ExternalValues(values, available)
        )
        val decisions = TraceLevel.entries.map { CoSecFlatEngine.evaluate(request, view, it).decision }
        // By text: each evaluation makes its own AttributeMissing.
        decisions.distinctBy { it.toString() }.assert().describedAs("$decisions").hasSize(1)
        return decisions.first()
    }

    @Test
    fun `the default boundary's attributes are planned, and the provider's answer decides`() {
        val view = view(platform(boundary("fence", inFence(true)), default = "fence"))
        plan(view).assert().containsExactly(inFence.path)
        decide(view, mapOf(inFence.path to true)).assert().isEqualTo(permit)
        decide(view, mapOf(inFence.path to false))
            .assert().isEqualTo(Decision.Deny(StatementRef.Boundary(PolicyId("fence"), TenantId("acme"))))
    }

    @Test
    fun `a boundary's policy condition is planned with its statements`() {
        val guarded = boundary("fence", inFence(true), guard = Condition.Eq(country.path, Literal.Str("NZ")))
        val view = view(platform(guarded, default = "fence"))
        plan(view).assert().containsExactlyInAnyOrder(inFence.path, country.path)
        decide(view, mapOf(inFence.path to true, country.path to "NZ")).assert().isEqualTo(permit)
        decide(view, mapOf(inFence.path to true, country.path to "AU")).assert().isInstanceOf(Decision.Deny::class.java)
    }

    @Test
    fun `an attribute that cannot be fetched follows the boundary statement's onMissing, else fails closed`() {
        val closed = view(platform(boundary("fence", inFence(true)), default = "fence"))
        val unavailable = decide(closed, emptyMap(), available = emptySet()) as Decision.Indeterminate
        (unavailable.cause as Cause.BoundaryUnavailable).boundary.assert().isEqualTo(PolicyId("fence"))
        val lapsing = view(platform(boundary("fence", inFence(true), onMissing = OnMissing.NOMATCH), default = "fence"))
        decide(lapsing, emptyMap(), available = emptySet())
            .assert().isEqualTo(Decision.Deny(StatementRef.Boundary(PolicyId("fence"), TenantId("acme"))))
    }

    @Test
    fun `a boundary that does not hold the request plans nothing`() {
        val fence = boundary("fence", inFence(true))
        val open = boundary("open", null)
        val platform = platform(fence, open, default = "fence")
        // The tenant's own boundary replaces the default: the default's attributes are not the request's.
        val ownBoundary = CompiledDocument.tenant(TenantId("acme"), emptyList(), emptyList(), emptyList(), "open")
        plan(view(platform, ownBoundary)).assert().isEmpty()
        // The platform tenant has no boundary.
        val platformTenant = flatRequest(tenant = TenantId.PLATFORM.value)
        plan(view(platform, tenant(tenant = TenantId.PLATFORM.value)), platformTenant).assert().isEmpty()
        // A boundary statement for other actions is not the request's either.
        plan(
            view(platform(boundary("orders", inFence(true), action = "order:*"), default = "orders"))
        ).assert().isEmpty()
    }

    @Test
    fun `a tenant's own boundary is planned`() {
        val platform = platform(boundary("open", null), boundary("fence", inFence(true)), default = "open")
        val fenced = CompiledDocument.tenant(TenantId("acme"), emptyList(), emptyList(), emptyList(), "fence")
        plan(view(platform, fenced)).assert().containsExactly(inFence.path)
        plan(view(platform)).assert().isEmpty()
    }
}
