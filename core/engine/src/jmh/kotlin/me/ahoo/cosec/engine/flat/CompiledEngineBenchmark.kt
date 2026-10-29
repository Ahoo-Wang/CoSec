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
 * The candidate index (RFC 0005 §9.3): `statements` compiled statements behind the bindings that apply to the request,
 * spread over ten platform policies with an `Anyone` binding each. Statement `k` denies when `k` is even.
 *
 * `namespace`: `http`, statement `k` is `http:GET` on `http:path:/r<k>/items/{id}`, and the request
 * `GET /r<k>/items/7`; `service`, it is `order:op<k>` on `order/{id}`, and the request `order:op<k>` on order `7`.
 * Scenarios: `allow`, the last ALLOW matches, so both passes look up all ten policies; `deny`, the last DENY matches;
 * `none`, nothing does. Each evaluation parses the request path.
 *
 * Expected: B/op the same for every `statements`; throughput at 10,000 statements at least half of that at 10.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class CompiledEngineBenchmark {
    @Param("allow", "deny", "none")
    lateinit var scenario: String

    @Param("10", "1000", "10000", "100000")
    var statements: Int = 0

    @Param("http", "service")
    lateinit var namespace: String

    private lateinit var request: FlatRequest
    private lateinit var view: SnapshotView

    @Setup
    fun setup() {
        val compiler = PolicyCompiler()
        val perPolicy = maxOf(1, statements / POLICIES)
        val policies = (0 until POLICIES).map { p ->
            val id = PolicyId("p$p")
            val compiled = (0 until perPolicy).map { s ->
                compiler.statement(id, s, statement(p * perPolicy + s))
            }
            CompiledPolicy(id, null, compiled)
        }
        val bindings = policies.map {
            Binding(BindingId("b-${it.id}"), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), it.policyId)
        }
        view = SnapshotView(
            CompiledDocument.platform(policies, bindings),
            null,
            CompiledDocument.tenant(TenantId("acme"), emptyList(), emptyList()),
        )
        val total = POLICIES * perPolicy
        val target = when (scenario) {
            "allow" -> if ((total - 1) % 2 == 1) total - 1 else total - 2
            "deny" -> if ((total - 1) % 2 == 0) total - 1 else total - 2
            "none" -> -1
            else -> error("Unknown scenario $scenario")
        }
        request = request(target)
        val decision = evaluate().decision
        val expected = when (scenario) {
            "allow" -> decision is Decision.Permit
            "deny" -> decision is Decision.Deny && decision.by != null
            else -> decision == Decision.Deny(null)
        }
        check(expected) { "Scenario $scenario decided $decision" }
        check(decision == CoSecFlatEngine.evaluate(request, view, TraceLevel.FULL).decision)
    }

    @Benchmark
    fun evaluate(): FlatEvaluation = CoSecFlatEngine.evaluate(request, view)

    private fun statement(k: Int): StatementSource {
        val effect = if (k % 2 == 0) Effect.DENY else Effect.ALLOW
        return if (namespace == "http") {
            StatementSource("s$k", effect, listOf("http:GET"), listOf(ResourceSource("http:path:/r$k/items/{id}")))
        } else {
            StatementSource("s$k", effect, listOf("order:op$k"), listOf(ResourceSource("order/{id}")))
        }
    }

    private fun request(target: Int): FlatRequest {
        val (action, resource) = if (namespace == "http") {
            Action("http", "GET") to Resource("http", if (target < 0) "/nothing/items/7" else "/r$target/items/7", emptyMap())
        } else {
            Action("order", if (target < 0) "none" else "op$target") to Resource("order", "7", emptyMap())
        }
        val memberships = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())
        val subject = ScopeSubject(TenantId("acme"), authenticated = true, memberships)
        val resolution = ScopeResolver.resolve(subject, DeclaredScope("", ""), HostScope(null))
        val authz = AuthzRequest(
            subject = Subject("user", "acme", true, emptySet(), emptySet(), emptyMap(), root = false, principalBindings = true, breakGlass = true),
            scope = Scope.declared("acme", "", ""),
            action = action,
            resource = resource,
            environment = Environment("127.0.0.1", "request", "device", blocked = false),
        )
        return FlatRequest(authz, (resolution as ScopeResolution.Resolved).scope, memberships, time = 0)
    }

    private companion object {
        const val POLICIES = 10
    }
}
