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
 * whole glob budget, [CompiledDocument.MAX_GLOB_UNITS], on DENYs bound to `Anyone` whose patterns have no literal
 * start, so every one is a candidate for every path. The request's path is one segment of 256 characters, the longest
 * `PathNormalizer` lets through. No statement decides: the evaluation reads every candidate and ends in an implicit deny.
 *
 * `scenario`, each the worst case of one path (review M2 of #1200); literals are case-insensitive, which compares slower:
 * - `literal`: the segment `*ab*` against `a…a`. A literal followed by a run is compared at every position, and this length
 *   costs the most per unit.
 * - `capture-literal`: `{v}ab*`, its value read by the condition, against `a…a`.
 * - `alternating`: `{v}a*a*a*a*a*a*a*b` against `a…ab`, which matches and is captured: seven literal rows at every
 *   position, and a 248-character value per statement.
 * - `captures`: `*{a}*{c}*{d}*{e}*{f}*{g}*{h}x{v}` against `xa…a`, which matches and is captured. Its only literal
 *   sits at the start, which made the backtracking this replaced give up every end of every run: 232 ms per request.
 * - `bindings`: `captures`, its policy bound 64 times (review M1): each pass evaluates it once (`ALG-9`).
 * - `pairs`: `{v}?*?*?*?*?*?*?*?`, sixteen tokens and no literal, against 128 surrogate pairs (review L6 of #1200), which
 *   matches and is captured. A value with a pair takes the row of code point boundaries, and every `?` row a second
 *   pass that reads two characters at each pair. The slowest of these inputs: about 0.5 ms and 107 KB per request on a
 *   CI runner where `literal` takes 0.33 ms (run 42348708014).
 *
 * The conditions read `resource.var.v` and are false, or, for `literal`, read the device id and are false. These inputs
 * are the worst in time; they allocate at most about 107 KB (`pairs`). In allocation the worst is the smallest capturing glob,
 * `{v}a`, at three units: about 0.25 MB per request with ASCII values and about 0.6 MB with values outside Latin-1, the
 * captured values (review round 2 of #1200), in at most about `literal`'s time.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class GlobBoundBenchmark {
    @Param("literal", "capture-literal", "alternating", "captures", "bindings", "pairs")
    lateinit var scenario: String

    private lateinit var request: FlatRequest
    private lateinit var view: SnapshotView

    @Setup
    fun setup() {
        val compiler = PolicyCompiler()
        val id = PolicyId("globs")
        val a = "a".repeat(SEGMENT_LENGTH)
        val (pattern, path) = when (scenario) {
            "literal" -> "/*ab*" to "/$a"
            "capture-literal" -> "/{v}ab*" to "/$a"
            "alternating" -> "/{v}a*a*a*a*a*a*a*b" to "/" + a.drop(1) + "b"
            "captures", "bindings" -> "/*{a}*{c}*{d}*{e}*{f}*{g}*{h}x{v}" to "/x" + a.drop(1)
            "pairs" -> "/{v}" + "?*".repeat(PAIR_RUNS) + "?" to "/" + PAIR.repeat(SEGMENT_LENGTH / PAIR.length)
            else -> error("Unknown scenario $scenario")
        }
        val units = HttpPathPattern.compile(pattern).globUnits
        val condition = if (scenario == "literal") {
            Condition.Eq("environment.device", Literal.Str("never"))
        } else {
            Condition.Eq("resource.var.v", Literal.Str("never"))
        }
        val resource = ResourceSource("http:path:$pattern", caseSensitive = false)
        val statements = (0 until CompiledDocument.MAX_GLOB_UNITS / units).map { k ->
            compiler.statement(id, k, StatementSource("s$k", Effect.DENY, listOf("http:GET"), listOf(resource), condition))
        }
        val policy = CompiledPolicy(id, null, statements)
        check(CompiledDocument.MAX_GLOB_UNITS - policy.globUnits < units)
        val bindings = (0 until if (scenario == "bindings") BINDINGS else 1).map {
            Binding(BindingId("b$it"), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), id)
        }
        view = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            null,
            CompiledDocument.tenant(TenantId("acme"), listOf(policy), bindings),
        )
        request = request(path)
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
        const val SEGMENT_LENGTH = 256
        const val BINDINGS = 64
        const val PAIR = "\uD83D\uDE00"

        /** `?*` seven times between `{v}` and a final `?`: sixteen tokens, the bound. */
        const val PAIR_RUNS = 7
    }
}
