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

package me.ahoo.cosec.gateway

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.audit.AuditRequest
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.identity.IdentityCodec
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** The gateway bridge: the route id reaches the pipeline, and a permitted request goes downstream with its id. */
class PipelineGatewayFilterTest {
    private object Views : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            null,
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    @Test
    fun aPermitGoesDownstreamWithItsRequestIdAndTheRouteReachesThePipeline() {
        val engine = object : FlatEngine {
            override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel): FlatEvaluation {
                return FlatEvaluation(
                    Decision.Permit(
                        StatementRef.Policy(
                            PolicyId("p"),
                            0,
                            "s",
                            BindingRef.Bound(BindingId("g"), DocumentKind.PLATFORM)
                        )
                    ),
                    null,
                )
            }
        }
        val authenticator = Authenticator { AuthnResult.Anonymous }
        val pipeline = Pipeline(PipelineComponents(authenticator, SuspendingSnapshotSource(Views), engine = engine))
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/orders").header("CoSec-Request-Id", "r-1")
        )
        val route = Route.async().id("orders").uri(URI.create("http://orders")).predicate { true }.build()
        exchange.attributes[GATEWAY_ROUTE_ATTR] = route
        var downstream: List<String>? = null
        PipelineGatewayFilter(pipeline).filter(exchange) { next ->
            downstream = next.request.headers["CoSec-Request-Id"]
            Mono.empty()
        }.block(Duration.ofSeconds(5))
        downstream.assert().isEqualTo(listOf("r-1"))
        // An id the pipeline does not accept is replaced, and the backend sees only the replacement.
        val forged = MockServerWebExchange.from(MockServerHttpRequest.get("/orders").header("CoSec-Request-Id", "a b"))
        PipelineGatewayFilter(pipeline).filter(forged) { next ->
            downstream = next.request.headers["CoSec-Request-Id"]
            Mono.empty()
        }.block(Duration.ofSeconds(5))
        downstream!!.single().assert().isNotEqualTo("a b")
        PipelineGatewayFilterProbe.read(PipelineGatewayFilter(pipeline), exchange).assert().isEqualTo("orders")
    }

    private val permit = object : FlatEngine {
        override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel): FlatEvaluation =
            FlatEvaluation(
                Decision.Permit(
                    StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Bound(BindingId("g"), DocumentKind.PLATFORM))
                ),
                null,
            )
    }

    private val alice = Authenticator {
        AuthnResult.Authenticated(
            AuthenticatedPrincipal(
                PrincipalId("alice"),
                TenantId("acme"),
                MembershipSource.Claims(Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())),
                emptyMap(),
                Identity.Credential(Identity.Method.JWT, "t1", java.time.Instant.now().plusSeconds(3600)),
                breakGlass = true,
                issuedAt = null,
                apiKeyCredential = false,
            ),
            "token",
        )
    }

    private fun pipeline(authenticator: Authenticator, issuer: IdentityIssuer?) = Pipeline(
        PipelineComponents(authenticator, SuspendingSnapshotSource(Views), engine = permit, identity = issuer)
    )

    /** What the backend receives of [request] through [filter]. */
    private fun forwarded(
        filter: PipelineGatewayFilter,
        request: MockServerHttpRequest.BaseBuilder<*>,
        route: Route? = null
    ): HttpHeaders {
        val exchange = MockServerWebExchange.from(request)
        route?.let { exchange.attributes[GATEWAY_ROUTE_ATTR] = it }
        var headers: HttpHeaders? = null
        filter.filter(exchange) { next ->
            headers = next.request.headers
            Mono.empty()
        }.block(Duration.ofSeconds(5))
        return headers!!
    }

    @Test
    fun `a client's identity never reaches a backend, whatever the pipeline forwards`() {
        val forged = { MockServerHttpRequest.get("/orders").header(IdentityCodec.HEADER, "forged") }
        // Even a pipeline that forwards nothing: the filter strips the header before anything reads it.
        forwarded(PipelineGatewayFilter(pipeline(alice, null)), forged())[IdentityCodec.HEADER].assert().isNull()
        forwarded(
            PipelineGatewayFilter(pipeline(Authenticator { AuthnResult.Anonymous }, IdentityIssuer())),
            forged()
        )[IdentityCodec.HEADER].assert().isNull()
        val verified = forwarded(PipelineGatewayFilter(pipeline(alice, IdentityIssuer())), forged())
        IdentityCodec.decode(verified.getFirst(IdentityCodec.HEADER)!!).subject.assert().isEqualTo("alice")
    }

    @Test
    fun `a route may opt out, and the Authorization header stays at the gateway unless asked for`() {
        val thirdParty = Route.async().id("partner").uri(URI.create("http://partner")).predicate { true }
            .metadata(PipelineGatewayFilter.IDENTITY_METADATA, false).build()
        val request = { MockServerHttpRequest.get("/orders").header(HttpHeaders.AUTHORIZATION, "token") }
        val filter = PipelineGatewayFilter(pipeline(alice, IdentityIssuer()))
        forwarded(filter, request(), thirdParty)[IdentityCodec.HEADER].assert().isNull()
        // Least privilege: a service behind needs no token, so the client's stays at the gateway unless asked for.
        forwarded(filter, request())[HttpHeaders.AUTHORIZATION].assert().isNull()
        val keeping = PipelineGatewayFilter(pipeline(alice, IdentityIssuer()), forwardAuthorization = true)
        forwarded(keeping, request())[HttpHeaders.AUTHORIZATION].assert().isEqualTo(listOf("token"))
    }
}

/** Reads the facts the gateway filter hands to the pipeline. */
private object PipelineGatewayFilterProbe {
    fun read(filter: PipelineGatewayFilter, exchange: MockServerWebExchange): String? {
        val method = PipelineGatewayFilter::class.java.getDeclaredMethod(
            "read",
            org.springframework.web.server.ServerWebExchange::class.java
        )
        method.isAccessible = true
        val facts = method.invoke(filter, exchange) as me.ahoo.cosec.runtime.http.HttpFacts
        return facts.requestAttributes[AuditRequest.ROUTE_ID_ATTRIBUTE_KEY]
    }
}
