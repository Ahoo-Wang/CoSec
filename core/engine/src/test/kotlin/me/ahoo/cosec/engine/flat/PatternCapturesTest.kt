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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.CallLog
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.Obligation
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * A statement with several resource patterns means exactly what one statement per pattern would mean (ALG-10): a
 * condition that reads a capture is tested against each matching pattern's captures, in declared order.
 */
class PatternCapturesTest {
    private val compiler = PolicyCompiler()
    private val allowAll = compiler.compile(
        PolicyId("allow"),
        null,
        listOf(StatementSource("a", Effect.ALLOW, listOf("*")))
    )

    private fun decide(vararg policies: CompiledPolicy, path: String): Decision {
        val bindings = policies.map { anyone("b-${it.policyId.value}", it.policyId.value) } + anyone("b-allow", "allow")
        val view = SnapshotView(CompiledDocument.platform(policies.toList() + allowAll, bindings), null, tenant())
        val request = flatRequest(path = path)
        return TraceLevel.entries.map { CoSecFlatEngine.evaluate(request, view, it).decision }.distinct().single()
    }

    private fun deny(vararg patterns: String, condition: Condition) = compiler.compile(
        PolicyId("deny"),
        null,
        listOf(
            StatementSource("d", Effect.DENY, listOf("*"), patterns.map { ResourceSource("http:path:$it") }, condition)
        ),
    )

    private val vIsA = Condition.Eq("resource.var.v", Literal.Str("a"))

    @Test
    fun `a deny holds under any matching pattern, whatever their order`() {
        decide(
            deny("/a/{v}", "/{v}/b", condition = vIsA),
            path = "/a/b"
        ).assert().isInstanceOf(Decision.Deny::class.java)
        decide(
            deny("/{v}/b", "/a/{v}", condition = vIsA),
            path = "/a/b"
        ).assert().isInstanceOf(Decision.Deny::class.java)
    }

    @Test
    fun `a rest capture of a narrower pattern does not hide the wider one`() {
        val secret = Condition.Prefix("resource.var.p", Literal.Str("/files/secret"))
        decide(deny("/files/{*p}", "/{*p}", condition = secret), path = "/files/secret/x")
            .assert().isInstanceOf(Decision.Deny::class.java)
        decide(deny("/files/{*p}", "/{*p}", condition = secret), path = "/files/public/x")
            .assert().isInstanceOf(Decision.Permit::class.java)
    }

    @Test
    fun `without a holding pattern the deny misses`() {
        decide(
            deny("/a/{v}", "/{v}/b", condition = vIsA),
            path = "/c/b"
        ).assert().isInstanceOf(Decision.Permit::class.java)
        decide(
            deny("/x/{v}", "/{v}/y", condition = vIsA),
            path = "/a/b"
        ).assert().isInstanceOf(Decision.Permit::class.java)
    }

    @Test
    fun `a pattern whose condition fails makes the statement fail only when no other pattern holds`() {
        val key = "patterns-captures-fails"
        val error = IllegalStateException("broken")
        Probes.register(key, Probes.Probe(CallLog(), key) { throw error })
        // Under /x/{v} the condition reads the failing attribute; under /{v}/b it holds without reading it.
        val condition = Condition.Or(
            listOf(
                Condition.And(
                    listOf(
                        Condition.Eq("resource.var.v", Literal.Str("b")),
                        Condition.Exists("request.attributes.$key")
                    )
                ),
                Condition.Eq("resource.var.v", Literal.Str("x")),
            ),
        )
        decide(
            deny("/x/{v}", "/{v}/b", condition = condition),
            path = "/x/b"
        ).assert().isInstanceOf(Decision.Deny::class.java)
        val failed = decide(deny("/x/{v}", "/{v}/c", condition = condition), path = "/x/b")
        (failed as Decision.Indeterminate).cause.assert().isInstanceOf(Cause.MatcherFailed::class.java)
        (failed.cause as Cause.MatcherFailed).error.assert().isSameAs(error)
    }

    @Test
    fun `a policy condition that does not hold rules out every pattern`() {
        val deny = compiler.compile(
            PolicyId("deny"),
            Condition.Eq("scope.app", Literal.Str("elsewhere")),
            listOf(
                StatementSource(
                    "d",
                    Effect.DENY,
                    listOf("*"),
                    listOf(ResourceSource("http:path:/a/{v}"), ResourceSource("http:path:/{v}/b")),
                    vIsA,
                ),
            ),
        )
        decide(deny, path = "/a/b").assert().isInstanceOf(Decision.Permit::class.java)
    }

    @Test
    fun `only statements that read a capture and have several patterns test each pattern`() {
        fun statement(patterns: List<String>, condition: Condition?) = compiler.statement(
            PolicyId("p"),
            0,
            StatementSource("s", Effect.DENY, listOf("*"), patterns.map { ResourceSource("http:path:$it") }, condition),
        )
        statement(listOf("/a/{v}", "/{v}/b"), vIsA).eachPattern.assert().isTrue()
        statement(listOf("/a/{v}"), vIsA).eachPattern.assert().isFalse()
        statement(
            listOf("/a/{v}", "/{v}/b"),
            Condition.Eq("scope.app", Literal.Str("app"))
        ).eachPattern.assert().isFalse()
        statement(listOf("/a/{v}", "/{v}/b"), null).eachPattern.assert().isFalse()
    }

    @Test
    fun `a rate limit bucket comes from the first pattern under which the rule's condition holds`() {
        val rule = compiler.rateLimit(
            DocumentKind.PLATFORM,
            "",
            RateLimitSource(
                "by-v",
                StatementSource(
                    "by-v",
                    Effect.ALLOW,
                    listOf("*"),
                    listOf(ResourceSource("http:path:/a/{v}"), ResourceSource("http:path:/{v}/b")),
                    vIsA,
                ),
                "resource.var.v",
                RateLimitSpec(10.0),
            ),
        )
        val platform = CompiledDocument.platform(listOf(allowAll), listOf(anyone("b-allow", "allow")), listOf(rule))
        val view = SnapshotView(platform, null, tenant())
        val decisions = TraceLevel.entries.map {
            CoSecFlatEngine.evaluate(
                flatRequest(path = "/a/b"),
                view,
                it
            ).decision
        }
        val obligation = (decisions.distinct().single() as Decision.Permit).obligations.single() as Obligation.RateLimit
        obligation.bucket.assert().isEqualTo(CompiledRateLimit.keyedBucket("a"))
    }

    /**
     * A pattern that fails to match (a path that does not decode) is recorded alike for a statement of several patterns
     * and of one, also after the decision, where the trace evaluates only for itself.
     */
    @Test
    fun `a failing pattern is recorded alike for one pattern and several, at every trace level`() {
        fun allow(id: String, vararg patterns: String) = compiler.compile(
            PolicyId(id),
            null,
            listOf(
                StatementSource("a", Effect.ALLOW, listOf("*"), patterns.map { ResourceSource("http:path:$it") }, vIsA)
            ),
        )
        val several = allow("several", "/a/{v}", "/{v}/b")
        val one = allow("one", "/a/{v}")
        val deny = compiler.compile(PolicyId("deny"), null, listOf(StatementSource("d", Effect.DENY, listOf("*"))))
        val bindings = listOf(anyone("b-deny", "deny"), anyone("b-several", "several"), anyone("b-one", "one"))
        val view = SnapshotView(CompiledDocument.platform(listOf(deny, several, one), bindings), null, tenant())
        val request = flatRequest(path = "/a/%zz")
        val decisions = TraceLevel.entries.map { CoSecFlatEngine.evaluate(request, view, it).decision }
        decisions.distinct().single().assert().isInstanceOf(Decision.Deny::class.java)
        val trace = CoSecFlatEngine.evaluate(request, view, TraceLevel.EXHAUSTIVE).trace!!
        fun outcome(policy: String) = trace.candidates.single { it.ref.policy == PolicyId(policy) }
        outcome("several").outcome.assert().isEqualTo(outcome("one").outcome)
        outcome("several").error.assert().isInstanceOf(IllegalArgumentException::class.java)
        outcome("several").afterDecision.assert().isTrue()
    }
}
