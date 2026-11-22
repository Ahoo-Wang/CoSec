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

package me.ahoo.cosec.engine

import me.ahoo.cosec.annotations.InternalCoSecApi
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
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.net.URLEncoder

/**
 * What one request's path can cost a document that spends its whole glob budget ([CompiledDocument.MAX_GLOB_UNITS]) on
 * ALLOWs bound to `Anyone` that never match (#1853, `PIP-5`), now that a segment is bounded decoded and a path as
 * received. Literals are case-insensitive, which compares slower; each glob is the costliest per unit for its input.
 *
 * - `before`: `*ab*` against one segment of 256 `a`, `GlobBoundBenchmark`'s `literal`: the worst before #1853.
 * - `eight-segments`: `*a*` seven times and `*ab*` against eight segments of 256 `a`: a path of the longest
 *   segments, every glob segment scanned.
 * - `decoded-cjk`: `*标b*` against 227 CJK characters, decoded (2,044 characters received, the most a segment holds).
 * - `decoded-cjk-256`: the same at 256 CJK characters, which the path's bound keeps out (2,305 received).
 * - `raw-256`: `decodeAndParseSegments: false`, `*ab*` against 256 `a`: the worst such a pattern still reads.
 * - `raw-escapes`: `decodeAndParseSegments: false`, `*` + `%61` × 32 + `x*` against `%61` × 256 (768 characters as
 *   received, 256 decoded).
 * - `raw-cjk`: `decodeAndParseSegments: false`, `*` + `%E6%A0%87` × 11 + `x*` against 227 CJK characters as received
 *   (2,043 characters).
 * - `raw-cjk-short`: as `raw-cjk`, with `*%ex*`.
 * - `raw-plain`: `*ab*` matched as received against 2,047 `a`, which `PathNormalizer` refuses.
 *
 * A literal row checks its first character at every position, whatever the text, so a glob costs its units times the
 * segment's length as the pattern reads it. Before such a pattern was bounded as written (run 45002383640, one CI
 * runner, ms per request): `before` 0.31, `eight-segments` 0.27, `decoded-cjk` 0.40, `decoded-cjk-256` 0.44,
 * `raw-escapes` 0.19, `raw-cjk` 1.09, `raw-cjk-short` 3.0, `raw-plain` 2.6. A pattern that matches the path as
 * received now matches no path with a segment longer than 256 characters as written, so the `raw-*` inputs over it
 * cost almost nothing and `raw-256` is the worst it reads: `before`'s.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class SegmentBoundBenchmark {
    @Param(
        "before", "eight-segments", "decoded-cjk", "decoded-cjk-256",
        "raw-256", "raw-escapes", "raw-cjk", "raw-cjk-short", "raw-plain",
    )
    lateinit var scenario: String

    private lateinit var request: EngineRequest
    private lateinit var view: SnapshotView

    @Setup
    fun setup() {
        val a = "a".repeat(SEGMENT)
        val (pattern, path) = when (scenario) {
            "before" -> "/*ab*" to "/$a"
            "eight-segments" -> "/*a*".repeat(SEGMENTS - 1) + "/*ab*" to "/$a".repeat(SEGMENTS)
            "decoded-cjk" -> "/*${CJK}b*" to "/" + cjk(MAX_CJK)
            "decoded-cjk-256" -> "/*${CJK}b*" to "/" + cjk(SEGMENT)
            "raw-256" -> "/*ab*" to "/$a"
            "raw-escapes" -> "/*" + "%61".repeat(32) + "x*" to "/" + "%61".repeat(SEGMENT)
            "raw-cjk" -> "/*" + cjk(11) + "x*" to "/" + cjk(MAX_CJK)
            "raw-cjk-short" -> "/*%ex*" to "/" + cjk(MAX_CJK)
            "raw-plain" -> "/*ab*" to "/" + "a".repeat(PATH - 1)
            else -> error("Unknown scenario $scenario")
        }
        val raw = scenario.startsWith("raw-")
        val units = HttpPathPattern.compile(pattern).globUnits
        val resource = ResourceSource("http:path:$pattern", caseSensitive = false, decodeAndParseSegments = !raw)
        val compiler = PolicyCompiler()
        val id = PolicyId("globs")
        val statements = (0 until CompiledDocument.MAX_GLOB_UNITS / units).map { k ->
            compiler.statement(id, k, StatementSource("s$k", Effect.ALLOW, listOf("http:GET"), listOf(resource)))
        }
        val policy = CompiledPolicy(id, null, statements)
        check(CompiledDocument.MAX_GLOB_UNITS - policy.globUnits < units)
        view = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            null,
            CompiledDocument.tenant(
                TenantId("acme"),
                listOf(policy),
                listOf(Binding(BindingId("b"), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), id)),
            ),
        )
        request = request(path)
        val decision = evaluate().decision
        check(decision == Decision.Deny(null)) { "Scenario $scenario decided $decision" }
    }

    @Benchmark
    fun evaluate(): Evaluation = CoSecEngine.evaluate(request, view)

    private fun request(path: String): EngineRequest {
        val memberships = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())
        val subject = ScopeSubject(TenantId("acme"), authenticated = true, memberships)
        val resolution = ScopeResolver.resolve(subject, "", HostScope(null))
        val authz = AuthzRequest(
            subject = Subject("user", true, emptyMap(), principalBindings = true, breakGlass = true),
            action = Action("http", "GET"),
            resource = Resource("http", path, emptyMap()),
            environment = Environment("127.0.0.1", "request", "device", blocked = false),
        )
        return EngineRequest(authz, (resolution as ScopeResolution.Resolved).scope, memberships, time = 0)
    }

    private companion object {
        const val SEGMENT = 256
        const val SEGMENTS = 8
        const val PATH = 2048

        /** The most CJK characters one segment can hold within the path's bound: `/` and nine characters each. */
        const val MAX_CJK = 227
        const val CJK = "标"

        fun cjk(n: Int): String = URLEncoder.encode(CJK.repeat(n), Charsets.UTF_8)
    }
}
