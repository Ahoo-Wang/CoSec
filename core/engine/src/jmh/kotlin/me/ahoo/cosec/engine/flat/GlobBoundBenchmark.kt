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

import me.ahoo.cosec.engine.pattern.HttpPathPattern
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Scope
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * The most a tenant administrator's path patterns can cost one request (RC1-8, `DOC-4`): a document that spends its
 * whole glob budget, [CompiledDocument.MAX_GLOB_TOKENS], on DENYs bound to `Anyone` whose patterns have no literal
 * start, so every one is a candidate for every path. Each holds one segment of [HttpPathPattern.MAX_SEGMENT_TOKENS]
 * tokens, `/{v}a*a*a*a*a*a*a*b`, and the request's path is one segment of 256 characters, the longest
 * `PathNormalizer` lets through.
 *
 * `scenario`: `captures`, the path is `a…ab`, which every pattern matches, and each DENY's condition reads
 * `resource.var.v` and is false, so every match is captured as well; `nomatch`, the path is `a…a`, which every pattern
 * scans to its end and rejects. No statement decides: the evaluation reads every candidate and ends in an implicit deny.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class GlobBoundBenchmark {
    @Param("captures", "nomatch")
    lateinit var scenario: String

    private lateinit var request: FlatRequest
    private lateinit var view: SnapshotView

    @Setup
    fun setup() {
        val compiler = PolicyCompiler()
        val id = PolicyId("globs")
        val pattern = "http:path:/{v}" + "a*".repeat(SEGMENT_STARS) + "b"
        check(HttpPathPattern.compile(pattern.removePrefix("http:path:")).globTokens == HttpPathPattern.MAX_SEGMENT_TOKENS)
        val condition = if (scenario == "captures") Condition.Eq("resource.var.v", Literal.Str("never")) else null
        val statements = (0 until STATEMENTS).map { k ->
            compiler.statement(
                id,
                k,
                StatementSource("s$k", Effect.DENY, listOf("http:GET"), listOf(ResourceSource(pattern)), condition),
            )
        }
        val policy = CompiledPolicy(id, null, statements)
        check(policy.globTokens == CompiledDocument.MAX_GLOB_TOKENS)
        val binding = Binding(BindingId("all"), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), id)
        view = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            null,
            CompiledDocument.tenant(TenantId("acme"), listOf(policy), listOf(binding)),
        )
        val last = if (scenario == "captures") "b" else "a"
        request = request("/" + "a".repeat(SEGMENT_LENGTH - 1) + last)
        val decision = evaluate().decision
        check(decision == Decision.Deny(null)) { "Scenario $scenario decided $decision" }
    }

    @Benchmark
    fun evaluate(): FlatEvaluation = CoSecFlatEngine.evaluate(request, view)

    private fun request(path: String): FlatRequest {
        val memberships = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())
        val subject = ScopeSubject(TenantId("acme"), authenticated = true, memberships)
        val resolution = ScopeResolver.resolve(subject, DeclaredScope("", ""), HostScope(null))
        val authz = AuthzRequest(
            subject = Subject(
                "user",
                "acme",
                true,
                emptySet(),
                emptySet(),
                emptyMap(),
                root = false,
                principalBindings = true,
                breakGlass = true,
            ),
            scope = Scope.declared("acme", "", ""),
            action = Action("http", "GET"),
            resource = Resource("http", path, emptyMap()),
            environment = Environment("127.0.0.1", "request", "device", blocked = false),
        )
        return FlatRequest(authz, (resolution as ScopeResolution.Resolved).scope, memberships, time = 0)
    }

    private companion object {
        /** `{v}`, then seven `a*` pairs, then `b`: 16 tokens. */
        const val SEGMENT_STARS = 7
        const val STATEMENTS = CompiledDocument.MAX_GLOB_TOKENS / HttpPathPattern.MAX_SEGMENT_TOKENS
        const val SEGMENT_LENGTH = 256
    }
}
