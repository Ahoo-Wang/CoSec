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
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.PipelineOptions
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
import org.springframework.http.HttpMethod
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

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

    private fun pipeline(
        authenticator: Authenticator,
        issuer: IdentityIssuer?,
        options: PipelineOptions = PipelineOptions(),
        engine: FlatEngine = permit,
    ) = Pipeline(
        PipelineComponents(
            authenticator,
            SuspendingSnapshotSource(Views),
            engine = engine,
            identity = issuer,
            options = options
        )
    )

    private fun route(uri: String, identity: Any? = null, app: String? = null): Route =
        Route.async().id("r").uri(URI.create(uri)).predicate { true }.apply {
            identity?.let { metadata(PipelineGatewayFilter.IDENTITY_METADATA, it) }
            app?.let { metadata(PipelineGatewayFilter.APP_METADATA, it) }
        }.build()

    private val lb = route("lb://orders")

    /** A GET of [uri] as written: `MockServerHttpRequest.get` would encode its escapes again. */
    private fun get(uri: String) = MockServerHttpRequest.method(HttpMethod.GET, URI.create(uri))

    /** What the backend receives of [request] through [filter], on [route]. */
    private fun sent(
        filter: PipelineGatewayFilter,
        request: MockServerHttpRequest.BaseBuilder<*>,
        route: Route? = lb,
    ): ServerHttpRequest {
        val exchange = MockServerWebExchange.from(request)
        route?.let { exchange.attributes[GATEWAY_ROUTE_ATTR] = it }
        var sent: ServerHttpRequest? = null
        filter.filter(exchange) { next ->
            sent = next.request
            Mono.empty()
        }.block(Duration.ofSeconds(5))
        return sent!!
    }

    private fun forwarded(
        filter: PipelineGatewayFilter,
        request: MockServerHttpRequest.BaseBuilder<*>,
        route: Route? = lb,
    ): HttpHeaders = sent(filter, request, route).headers

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
        // Without a route (a global filter always has one), nothing marks the request as receiving it.
        forwarded(PipelineGatewayFilter(pipeline(alice, IdentityIssuer())), forged(), null)[IdentityCodec.HEADER]
            .assert().isNull()
    }

    /** IDN-7, RC1-49: opt-in by scheme; metadata `true` opts any route in, `false` (or anything else) opts out. */
    @Test
    fun `only a load-balanced route, or one marked true, receives the identity`() {
        val filter = PipelineGatewayFilter(pipeline(alice, IdentityIssuer()))
        val request = { MockServerHttpRequest.get("/orders").header(IdentityCodec.HEADER, "forged") }
        fun identityOn(route: Route) = forwarded(filter, request(), route).getFirst(IdentityCodec.HEADER)
            ?.let { IdentityCodec.decode(it).subject }
        identityOn(route("lb://orders")).assert().isEqualTo("alice")
        identityOn(route("lb:ws://orders")).assert().isEqualTo("alice")
        identityOn(route("https://partner.example")).assert().isNull()
        identityOn(route("http://orders.internal")).assert().isNull()
        identityOn(route("https://orders.internal", identity = true)).assert().isEqualTo("alice")
        identityOn(route("https://orders.internal", identity = "true")).assert().isEqualTo("alice")
        identityOn(route("lb://orders", identity = false)).assert().isNull()
        identityOn(route("lb://orders", identity = "false")).assert().isNull()
        identityOn(route("lb://orders", identity = "TRUE")).assert().isNull()
        identityOn(route("lb://orders", identity = "yes")).assert().isNull()
        identityOn(route("LB://orders")).assert().isNull()
    }

    /** IDN-8, RC1-5: no credential goes downstream by default, the API key header under its configured name included. */
    @Test
    fun `the caller's credentials stay at the gateway unless asked for, and then go only where the identity goes`() {
        val renamed = PipelineOptions(apiKeyHeader = "X-Service-Key")
        val request = {
            get("/orders?page=2&authorization=t&%61uthorization=u")
                .header(HttpHeaders.AUTHORIZATION, "token")
                .header("X-Service-Key", "key")
                .header("X-API-Key", "other")
                .header(HttpHeaders.COOKIE, "theme=dark; authorization=c", "Authorization=d")
        }
        val stripping = PipelineGatewayFilter(pipeline(alice, IdentityIssuer(), renamed))
        val stripped = sent(stripping, request())
        stripped.headers[HttpHeaders.AUTHORIZATION].assert().isNull()
        stripped.headers["X-Service-Key"].assert().isNull()
        // R1-4: the cookie the pipeline reads a token from goes; a header left with no cookie goes too.
        stripped.headers[HttpHeaders.COOKIE].assert().isEqualTo(listOf("theme=dark"))
        // Not a credential header of this pipeline: it reads keys from the renamed one only.
        stripped.headers["X-API-Key"].assert().isEqualTo(listOf("other"))
        stripped.uri.rawQuery.assert().isEqualTo("page=2")
        stripped.uri.path.assert().isEqualTo("/orders")

        val keeping = PipelineGatewayFilter(pipeline(alice, IdentityIssuer(), renamed), forwardAuthorization = true)
        val kept = sent(keeping, request())
        kept.headers[HttpHeaders.AUTHORIZATION].assert().isEqualTo(listOf("token"))
        kept.headers["X-Service-Key"].assert().isEqualTo(listOf("key"))
        kept.headers[HttpHeaders.COOKIE].assert().isEqualTo(listOf("theme=dark; authorization=c", "Authorization=d"))
        kept.uri.rawQuery.assert().isEqualTo("page=2&authorization=t&%61uthorization=u")
        // A route that does not receive the identity gets none of them either, asked for or not.
        for (route in listOf(route("https://partner.example"), route("lb://orders", identity = false))) {
            val partner = sent(keeping, request(), route)
            partner.headers[HttpHeaders.AUTHORIZATION].assert().isNull()
            partner.headers["X-Service-Key"].assert().isNull()
            partner.headers[HttpHeaders.COOKIE].assert().isEqualTo(listOf("theme=dark"))
            partner.uri.rawQuery.assert().isEqualTo("page=2")
        }
        sent(keeping, request(), route("https://partner.example", identity = true))
            .headers[HttpHeaders.AUTHORIZATION].assert().isEqualTo(listOf("token"))
        // A query without the token is left as it came.
        sent(stripping, get("/orders?q=a%20b")).uri.rawQuery.assert().isEqualTo("q=a%20b")
        sent(stripping, get("/orders?authorization=t")).uri.rawQuery.assert().isNull()
        // Cookies without the token are left as they came; a request with only the token's cookie sends no Cookie.
        sent(stripping, get("/orders").header(HttpHeaders.COOKIE, "a=1;b=2")).headers[HttpHeaders.COOKIE]
            .assert().isEqualTo(listOf("a=1;b=2"))
        sent(stripping, get("/orders").header(HttpHeaders.COOKIE, "authorization=t")).headers
            .containsHeader(HttpHeaders.COOKIE).assert().isFalse()
        // The rest of the URI stays as it came: escapes, a fragment, and characters no builder would accept unencoded.
        val raw = sent(stripping, get("/a%2Fb?x=%E4%B8%AD&authorization=t&y=中#frag")).uri
        raw.rawPath.assert().isEqualTo("/a%2Fb")
        raw.rawQuery.assert().isEqualTo("x=%E4%B8%AD&y=中")
        raw.rawFragment.assert().isEqualTo("frag")
    }

    /** SCP-2, RC1-6: each route's app is its requests' host app, ahead of the gateway's; the client's only without. */
    @Test
    fun `two routes of different apps are each their own app`() {
        val scopes = CopyOnWriteArrayList<String>()
        val recording = object : FlatEngine {
            override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel): FlatEvaluation {
                scopes += request.scope.app.value
                return permit.evaluate(request, snapshot, trace)
            }
        }
        val shop = HostScope(AppId("shop"))
        val filter = PipelineGatewayFilter(pipeline(alice, IdentityIssuer(), engine = recording), host = shop)
        val orders = route("lb://orders", app = "order")
        val billing = route("lb://billing", app = "billing")
        val plain = route("lb://plain")
        fun status(route: Route, declared: String? = null): Int? {
            val exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/x").apply { declared?.let { header("CoSec-App-Id", it) } }
            )
            exchange.attributes[GATEWAY_ROUTE_ATTR] = route
            filter.filter(exchange) { Mono.empty() }.block(Duration.ofSeconds(5))
            return exchange.response.statusCode?.value()
        }
        status(orders).assert().isNull()
        status(billing).assert().isNull()
        status(plain).assert().isNull()
        scopes.assert().containsExactly("order", "billing", "shop")
        // The route's app is the host's: another declared app is rejected, as against a configured one.
        status(orders, "billing").assert().isEqualTo(403)
        status(billing, "billing").assert().isNull()
        status(plain, "order").assert().isEqualTo(403)
        // Without a route app or a host app, the client's declared app applies.
        val open = PipelineGatewayFilter(pipeline(alice, IdentityIssuer(), engine = recording))
        scopes.clear()
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").header("CoSec-App-Id", "order"))
        exchange.attributes[GATEWAY_ROUTE_ATTR] = plain
        open.filter(exchange) { Mono.empty() }.block(Duration.ofSeconds(5))
        scopes.assert().containsExactly("order")
    }

    /** RC1-6, RC1-15: the gateway passes the route's app as written; the pipeline refuses one that is not an app id. */
    @Test
    fun `a route app that cannot be an app id fails the route's requests`() {
        val filter = PipelineGatewayFilter(pipeline(alice, IdentityIssuer()))
        for (app in listOf("", "a:b", "a\nb")) {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"))
            exchange.attributes[GATEWAY_ROUTE_ATTR] = route("lb://orders", app = app)
            var called = false
            filter.filter(exchange) {
                called = true
                Mono.empty()
            }.block(Duration.ofSeconds(5))
            called.assert().describedAs(app).isFalse()
            exchange.response.statusCode?.value().assert().describedAs(app).isEqualTo(500)
            exchange.response.bodyAsString.block(Duration.ofSeconds(5)).assert()
                .contains("\"type\":\"https://cosec.ahoo.me/problems/internal-error\"")
        }
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
