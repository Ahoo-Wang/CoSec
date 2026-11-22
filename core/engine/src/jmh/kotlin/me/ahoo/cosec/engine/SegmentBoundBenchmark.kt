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
 * Reachable since #1853 (`PathNormalizer` lets these through):
 * - `before`: `*ab*` against one segment of 256 `a`, `GlobBoundBenchmark`'s `literal`: the worst before.
 * - `eight-segments`: `*a*` seven times and `*ab*` against eight segments of 256 `a`: a path of the longest
 *   segments, every glob segment scanned.
 * - `decoded-cjk`: `*标b*` against 227 CJK characters, decoded (2,044 characters received).
 * - `raw-escapes`: `decodeAndParseSegments: false`, `*` + `%61` × 32 + `x*` against `%61` × 256 (768 characters as
 *   received, 256 decoded): a long literal that matches at every escape.
 * - `raw-cjk`: `decodeAndParseSegments: false`, `*` + `%E6%A0%87` × 11 + `x*` against 227 CJK characters as received
 *   (2,043 characters), a long literal that matches at every character.
 * - `raw-cjk-short`: as `raw-cjk`, with `*%ex*`.
 *
 * Out of reach, for comparison: `decoded-cjk-256` (256 CJK characters, 2,305 received: over the path's bound) and
 * `raw-plain` (`*ab*` matched as received against 2,047 `a`: over the segment's bound once decoded).
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class SegmentBoundBenchmark {
    @Param("before", "eight-segments", "decoded-cjk", "raw-escapes", "raw-cjk", "raw-cjk-short", "decoded-cjk-256", "raw-plain")
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
