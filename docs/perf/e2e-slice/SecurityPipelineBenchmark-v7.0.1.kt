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

package me.ahoo.cosec.pipeline

import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.RequestIdCapable
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.authorization.SimpleAuthorizationBenchmark
import me.ahoo.cosec.context.AUTHORIZATION_HEADER_KEY
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.context.request.RequestPaths
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.util.UUID

/**
 * 7.0's SecurityPipeline over SimpleAuthorizationBenchmark's data: the counterpart of 8.0's e2e PipelineBenchmark
 * (RFC 0008 §3.8). Scratch, for the comparison only; not part of 7.0.1.
 *
 * The request parser does what ReactiveRequestParser does (normalize the path, generate a request id); the token is
 * the e2e slice's fake (`principal@tenant:role,role|policy,policy`), so neither side verifies a JWT.
 */
@State(Scope.Benchmark)
open class SecurityPipelineBenchmark {
    @Param("global", "role", "none", "anonymous")
    lateinit var scenario: String

    class Raw(val method: String, val path: String, val headers: Map<String, String>)

    private lateinit var pipeline: SecurityPipeline<Raw>
    private lateinit var raw: Raw

    @Setup
    fun setup() {
        val data = SimpleAuthorizationBenchmark()
        data.scenario = if (scenario == "anonymous") "global" else scenario
        data.setup()
        val authorization = SimpleAuthorizationBenchmark::class.java.getDeclaredField("authorization")
            .apply { isAccessible = true }.get(data) as SimpleAuthorization
        val requestParser = object : RequestParser<Raw> {
            override fun parse(request: Raw): Request {
                val path = RequestPaths.normalize(request.path)
                val requestId = request.headers[RequestIdCapable.REQUEST_ID_KEY] ?: UUID.randomUUID().toString()
                return EvaluateRequest(path = path, method = request.method, headers = request.headers + (RequestIdCapable.REQUEST_ID_KEY to requestId))
            }
        }
        val contextParser = SecurityContextParser { request ->
            val token = request.getHeader(AUTHORIZATION_HEADER_KEY).removePrefix("Bearer ")
            if (token.isEmpty()) {
                return@SecurityContextParser SimpleSecurityContext.anonymous()
            }
            val (claims, attached) = token.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val (who, roles) = claims.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val (principal, tenant) = who.split('@', limit = 2).let { it[0] to it[1] }
            val simple = SimplePrincipal(
                principal,
                attached.split(',').filter { it.isNotEmpty() }.toSet(),
                roles.split(',').filter { it.isNotEmpty() }.toSet(),
            )
            SimpleSecurityContext(SimpleTenantPrincipal(simple, SimpleTenant(tenant)))
        }
        pipeline = SecurityPipeline(requestParser, contextParser, authorization)
        val headers = buildMap {
            put(AppIdCapable.APP_ID_KEY, "app")
            if (scenario != "anonymous") {
                put(AUTHORIZATION_HEADER_KEY, "Bearer user@acme:viewer,admin|principal-a,principal-b")
            }
        }
        val path = when (scenario) {
            "global", "anonymous" -> "/public/resource3/page"
            "role" -> "/orders/op49/detail"
            else -> "/unknown/path"
        }
        raw = Raw("GET", path, headers)
        val decision = evaluate()
        check(decision.allowed == (scenario != "none")) { "Scenario $scenario: $decision" }
    }

    @Benchmark
    fun evaluate(): SecurityDecision = pipeline.evaluate(raw).block()!!
}
