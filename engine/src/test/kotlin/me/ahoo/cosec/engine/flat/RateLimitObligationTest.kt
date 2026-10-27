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
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.Obligation
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/**
 * RFC 0006 §8.2: a permit carries the obligations of every rate limit rule that matches it, in document and rule order,
 * each with the bucket its own key reads; a deny or an indeterminate decision carries none (P15).
 */
class RateLimitObligationTest {
    private val fixture = FlatFixture()
    private val geo = Attribute.external("ext.geo.zone", AttributeType.STRING)
    private val compiler = PolicyCompiler(ConditionChecker(catalog = AttributeCatalog.of(listOf(geo))))
    private val limit = RateLimitSpec(10.0)

    private fun rule(
        id: String,
        key: String? = null,
        resource: String? = null,
        condition: Condition? = null,
        strict: Boolean = false,
        kind: DocumentKind = DocumentKind.PLATFORM,
        owner: String = "",
        onMissing: OnMissing? = null,
    ): CompiledRateLimit {
        val resources = listOfNotNull(resource?.let { ResourceSource(it) })
        val statement = StatementSource(id, Effect.ALLOW, listOf("*"), resources, condition, onMissing)
        return compiler.rateLimit(kind, owner, RateLimitSource(id, statement, key, limit, strict))
    }

    private fun permitting(vararg rules: CompiledRateLimit) =
        CompiledDocument.platform(listOf(fixture.policy("p", allow())), listOf(anyone("g", "p")), rules.toList())

    private fun obligations(view: SnapshotView, request: FlatRequest = flatRequest()): List<Obligation> {
        val decisions = TraceLevel.entries.map { CoSecFlatEngine.evaluate(request, view, it).decision }
        decisions.distinct().assert().hasSize(1)
        return (decisions.first() as Decision.Permit).obligations
    }

    @Test
    fun `a permit carries the obligations of the rules it matches, in order`() {
        val platform = permitting(
            rule("shared"),
            rule("by-ip", key = "environment.ip"),
            rule("by-order", key = "resource.var.id", resource = "http:path:/orders/{id}"),
            rule("elsewhere", resource = "http:path:/accounts/**"),
        )
        obligations(SnapshotView(platform, null, tenant())).assert().isEqualTo(
            listOf(
                Obligation.RateLimit("shared", "global", limit, false),
                Obligation.RateLimit("by-ip", "g:127.0.0.1", limit, false),
                Obligation.RateLimit("by-order", "g:1", limit, false),
            )
        )
    }

    @Test
    fun `an absent key value is the empty bucket`() {
        val platform = permitting(rule("by-tier", key = "subject.attributes.tier"))
        obligations(SnapshotView(platform, null, tenant())).single()
            .assert().isEqualTo(Obligation.RateLimit("by-tier", "g:", limit, false))
    }

    /** P15: a deny spends nothing, so it carries no obligation. */
    @Test
    fun `a deny carries no obligation`() {
        val platform = CompiledDocument.platform(
            listOf(fixture.policy("d", deny())),
            listOf(anyone("g", "d")),
            listOf(rule("shared")),
        )
        CoSecFlatEngine.evaluate(flatRequest(), SnapshotView(platform, null, tenant())).decision
            .assert().isInstanceOf(Decision.Deny::class.java)
    }

    @Test
    fun `app and tenant rules count in buckets of their own, after the platform's`() {
        val platform = permitting(rule("r"))
        val app = CompiledDocument.app(
            AppId("app"),
            emptyList(),
            emptyList(),
            listOf(rule("r", kind = DocumentKind.APP, owner = "app"))
        )
        val acme = CompiledDocument.tenant(
            TenantId("acme"),
            emptyList(),
            emptyList(),
            listOf(rule("r", kind = DocumentKind.TENANT, owner = "acme")),
        )
        val request = flatRequest(roles = listOf("viewer"))
        obligations(SnapshotView(platform, app, acme), request).map { (it as Obligation.RateLimit).ruleId }
            .assert().containsExactly("r", "app:app/r", "tenant:acme/r")
    }

    @Test
    fun `an external attribute that cannot be fetched leaves the bucket to strictFailure`() {
        val zone = Condition.Eq("ext.geo.zone", Literal.Str("eu"))
        val platform = permitting(
            rule("by-zone", key = "ext.geo.zone", strict = true),
            rule("in-zone", condition = zone),
            rule("skipped", condition = zone, onMissing = OnMissing.NOMATCH),
        )
        obligations(SnapshotView(platform, null, tenant())).assert().isEqualTo(
            listOf(
                Obligation.RateLimit("by-zone", null, limit, true),
                Obligation.RateLimit("in-zone", null, limit, false),
            )
        )
        val fetched = flatRequest().let {
            FlatRequest(
                it.request,
                it.scope,
                it.memberships,
                it.time,
                it.attributes,
                ExternalValues(mapOf(geo.path to "eu"), setOf(geo.path))
            )
        }
        obligations(SnapshotView(platform, null, tenant()), fetched).assert().isEqualTo(
            listOf(
                Obligation.RateLimit("by-zone", "g:eu", limit, true),
                Obligation.RateLimit("in-zone", "global", limit, false),
                Obligation.RateLimit("skipped", "global", limit, false),
            )
        )
        // The plan fetches what the rules read.
        CoSecFlatEngine.plan(
            flatRequest(),
            SnapshotView(platform, null, tenant())
        ).attributes.assert().containsExactly(geo)
    }

    @Test
    fun `rules that would not count what they say do not compile`() {
        assertThrownBy<CompileException> { rule("bad id") }
        assertThrownBy<CompileException> { rule("unknown", key = "nothing.here") }
        assertThrownBy<CompileException> { rule("bool", key = "subject.authenticated") }
        assertThrownBy<CompileException> {
            rule(
                "uncaptured",
                key = "resource.var.id",
                resource = "http:path:/orders/**"
            )
        }
        assertThrownBy<CompileException> { rule("bare", resource = "order") }
        assertThrownBy<CompileException> {
            rule(
                "lapsing",
                condition = Condition.Eq("ext.geo.zone", Literal.Str("eu")),
                onMissing = OnMissing.NOMATCH,
                kind = DocumentKind.TENANT,
                owner = "acme"
            )
        }
        assertThrownBy<IllegalArgumentException> { permitting(rule("twice"), rule("twice")) }
    }
}
