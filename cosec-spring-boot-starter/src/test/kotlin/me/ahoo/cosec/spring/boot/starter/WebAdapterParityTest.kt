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

package me.ahoo.cosec.spring.boot.starter

import kotlinx.coroutines.awaitCancellation
import me.ahoo.cosec.api.audit.AuditRequest
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.gateway.PipelineGatewayFilter
import me.ahoo.cosec.identity.Identity
import me.ahoo.cosec.identity.IdentityCodec
import me.ahoo.cosec.limiter.RateLimitResult
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.PipelineOptions
import me.ahoo.cosec.runtime.RequestGate
import me.ahoo.cosec.runtime.attribute.AttributeDeclaration
import me.ahoo.cosec.runtime.attribute.AttributeProvider
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.attribute.AttributeRequest
import me.ahoo.cosec.runtime.authn.AuthenticatedPrincipal
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.event.TraceContext
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.runtime.ratelimit.RateLimiter
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.servlet.PipelineServletFilter
import me.ahoo.cosec.servlet.ServletRequests.getSecurityContext
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDocumentCompiler
import me.ahoo.cosec.webflux.PipelineWebFilter
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One decision path (RFC 0006 §6.3, `PIP-1`): the WebFlux filter, the gateway filter and the servlet filter, over the
 * same coroutine pipeline, answer every scenario byte for byte alike: status, `Content-Type`, `CoSec-Request-Id` and the
 * problem body, every problem type of §6.1 included. Their decision events agree on the request id, the trace, the
 * subject and the scope, and the response body names the same trace.
 *
 * Only the 8.0 pipeline runs here (RFC 0008 §4.1). `too-many-requests` joins with RFC 0006 PR-5.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebAdapterParityTest {
    /** What one adapter wrote, and what it let through. */
    /** What one adapter wrote: every header (names folded to lower case, values in order) and the raw body bytes. */
    data class Written(
        val status: Int,
        val headers: Map<String, List<String>>,
        val bytes: List<Byte>,
        val proceeded: Boolean,
        val principal: String?,
        val scope: String?,
    ) {
        val contentType: String? get() = headers["content-type"]?.single()
        val requestId: String? get() = headers["cosec-request-id"]?.single()
        val body: String get() = String(bytes.toByteArray(), Charsets.UTF_8)
    }

    class Scenario(
        val name: String,
        val method: String = "GET",
        val path: String,
        val headers: Map<String, String> = emptyMap(),
        val pipeline: String = "default",
        val status: Int,
        val type: String?,
    ) {
        override fun toString(): String = name
    }

    private val events = CopyOnWriteArrayList<DecisionEvent>()
    private val recorder = object : DecisionListener {
        override val name = "recorder"
        override val delivery = Delivery.REQUIRED

        override suspend fun onDecision(event: DecisionEvent) {
            events += event
        }
    }
    private val failingAudit = object : DecisionListener {
        override val name = "audit"
        override val delivery = Delivery.REQUIRED

        override suspend fun onDecision(event: DecisionEvent) = error("The audit store is down")
    }
    private val defaultListeners = DecisionListeners(listOf(recorder))
    private val failingListeners = DecisionListeners(listOf(failingAudit))
    private val trace = TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", true)
    private val contributors = listOf(BridgeContextContributor { trace as CoroutineContext })

    @AfterAll
    fun close() {
        defaultListeners.cancel()
        failingListeners.cancel()
    }

    // --- the pipeline --------------------------------------------------------------------------------------------

    private val geo = object : AttributeProvider {
        override val attributes = listOf(AttributeDeclaration("ext.geo.inFence", AttributeType.BOOL))

        override suspend fun fetch(request: AttributeRequest, names: Set<String>): Map<String, Any> =
            error("The geo service is down")
    }
    private val attributes = AttributeProviders(listOf(geo))
    private val compiler = PolicyDocumentCompiler(attributes.catalog)

    private fun compile(key: SnapshotKey, body: String): CompiledDocument = compiler.compile(
        key,
        SnapshotDocument(
            2,
            1,
            Instant.parse("2026-10-26T00:00:00Z"),
            """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"$body}""".encodeToByteArray(),
        ),
    )

    private val platform = compile(
        SnapshotKey.Platform,
        ""","policies":[
            {"id":"public","statements":[{"effect":"allow","action":"http:GET","resource":"http:path:/public/**"}]},
            {"id":"no-delete","statements":[{"effect":"deny","action":"http:DELETE","resource":"http:path:/orders/**"}]},
            {"id":"no-debug","statements":[{"effect":"deny","action":"*","resource":"http:path:/public/**",
              "condition":{"eq":["http.header.X-Debug","1"]}}]},
            {"id":"geo","statements":[{"effect":"allow","action":"http:GET","resource":"http:path:/geo/**",
              "condition":{"eq":["ext.geo.inFence",true]}}]}],
          "bindings":[{"id":"g:public","subject":"*","app":"*","space":"*","policy":"public"},
                      {"id":"g:no-delete","subject":"*","app":"*","space":"*","policy":"no-delete"},
                      {"id":"g:no-debug","subject":"*","app":"*","space":"*","policy":"no-debug"},
                      {"id":"g:geo","subject":"*","app":"*","space":"*","policy":"geo"}],
          "rateLimits":[{"id":"limited","action":"*","resource":"http:path:/public/limited/**","permitsPerSecond":10}]""",
    )

    /** Has permits for every rule but `limited`, whose bucket is exhausted: the 429 path (RFC 0006 §8.3). */
    private val rateLimiter = RateLimiter { ruleId, _, _ ->
        val allowed = ruleId != "limited"
        RateLimitResult(allowed, 10, if (allowed) 9 else 0, java.time.Duration.ofSeconds(3))
    }
    private val app = compile(
        SnapshotKey.App("order"),
        ""","catalog":{"groups":[{"name":"order","permissions":[{"id":"read"}]}]},
          "policies":[{"id":"perm:order/read","statements":[{"effect":"allow","action":"http:*","resource":"http:path:/orders/**"}]}],
          "bindings":[{"id":"r:clerk:read","subject":{"role":"clerk"},"app":"order","space":"*","policy":"perm:order/read"}]""",
    )

    private val views = object : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView {
            val tenant = key.tenant ?: return SnapshotView(platform, null, null)
            if (tenant.value == "broken") {
                val down = Cause.SourceUnavailable(DocumentKind.TENANT, IllegalStateException("down"))
                return SnapshotView(platform, null, null, listOf(down))
            }
            val appDocument = key.app?.let {
                if (it.value == "order") app else CompiledDocument.app(it, emptyList(), emptyList())
            }
            return SnapshotView(platform, appDocument, CompiledDocument.tenant(tenant, emptyList(), emptyList()))
        }

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    private val authenticator = Authenticator { credentials ->
        val token = credentials.token?.removePrefix("Bearer ") ?: return@Authenticator AuthnResult.Anonymous
        when (token) {
            "expired" -> AuthnResult.Rejected(AuthnResult.RejectReason.TOKEN_EXPIRED)
            "invalid" -> AuthnResult.Rejected(AuthnResult.RejectReason.TOKEN_INVALID)
            "revoked" -> AuthnResult.Rejected(AuthnResult.RejectReason.TOKEN_REVOKED)
            "down" -> AuthnResult.Unavailable(IllegalStateException("The key store is down"))
            "slow" -> awaitCancellation()
            else -> {
                val (who, rest) = token.split('@', limit = 2)
                val (tenant, roles) = rest.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val memberships = if (roles == "provider") {
                    null
                } else {
                    Memberships(
                        Memberships.Source.CLAIMS,
                        mapOf(
                            SpaceId.DEFAULT to roles.split(',').filter { it.isNotEmpty() }.mapTo(LinkedHashSet(), ::RoleId)
                        ),
                        emptyList(),
                    )
                }
                AuthnResult.Authenticated(
                    AuthenticatedPrincipal(PrincipalId(who), TenantId(tenant), memberships),
                    "token"
                )
            }
        }
    }

    /** The host attributes the engine was given for each request: what conditions on `http.*` and `request.*` read. */
    private val hostAttributes = CopyOnWriteArrayList<List<String?>>()

    /** The engine, except that `/boom` is a defect: the 500 path. */
    private val engine = object : FlatEngine {
        override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel): FlatEvaluation {
            hostAttributes += HOST_ATTRIBUTES.map(request.attributes::string)
            check(request.request.resource.id != "/boom") { "A defect" }
            return CoSecFlatEngine.evaluate(request, snapshot, trace)
        }
    }

    /**
     * Only the deadline scenario runs under the short [timeout]: under it, a cold JVM's first request could miss the
     * deadline, become a 503 the REQUIRED recorder never hears, and fail a scenario that has nothing to do with it.
     */
    private fun pipeline(listeners: DecisionListeners, timeout: kotlin.time.Duration = 30.seconds) = Pipeline(
        PipelineComponents(
            authenticator = authenticator,
            snapshots = SuspendingSnapshotSource(views),
            membershipProvider = { _, _ -> error("The directory is down") },
            engine = engine,
            idGenerator = { "parity-request" },
            options = PipelineOptions(timeout = timeout, authenticationTimeout = 1.seconds),
            attributes = attributes,
            listeners = listeners,
            rateLimiter = rateLimiter,
        )
    )

    private val pipelines = mapOf(
        "default" to pipeline(defaultListeners),
        "failing-audit" to pipeline(failingListeners),
        "deadline" to pipeline(defaultListeners, timeout = 200.milliseconds),
    )

    /** What WebMvc and WebFlux bridge to: a pipeline, or a service behind a gateway's [IdentityGate]. */
    private val gates: Map<String, RequestGate> =
        pipelines + ("identity" to IdentityGate(idGenerator = { "parity-request" }))

    // --- the adapters --------------------------------------------------------------------------------------------

    private fun webflux(scenario: Scenario): Written = reactive(scenario) { exchange, record ->
        PipelineWebFilter(gates.getValue(scenario.pipeline), contributors)
            .filter(exchange, WebFilterChain { record(it.getSecurityContext()) })
    }

    private fun gateway(scenario: Scenario): Written = reactive(scenario) { exchange, record ->
        PipelineGatewayFilter(pipelines.getValue(scenario.pipeline), contributors)
            .filter(exchange, GatewayFilterChain { record(it.getSecurityContext()) })
    }

    private fun reactive(
        scenario: Scenario,
        filter: (MockServerWebExchange, (SecurityContext?) -> Mono<Void>) -> Mono<Void>,
    ): Written {
        val request = MockServerHttpRequest.method(HttpMethod.valueOf(scenario.method), URI.create(scenario.path))
            .remoteAddress(InetSocketAddress("127.0.0.1", 50000))
        scenario.headers.forEach { (name, value) -> request.header(name, value) }
        val exchange = MockServerWebExchange.from(request)
        var proceeded: SecurityContext? = null
        var called = false
        filter(exchange) { context ->
            called = true
            proceeded = context
            Mono.empty()
        }.block(Duration.ofSeconds(5))
        val response = exchange.response
        if (called) {
            response.setComplete().block(Duration.ofSeconds(5))
        }
        val bytes = DataBufferUtils.join(response.body)
            .map { buffer -> ByteArray(buffer.readableByteCount()).also { buffer.read(it) } }
            .defaultIfEmpty(ByteArray(0))
            .block(Duration.ofSeconds(5))!!
        val headers = java.util.TreeMap<String, List<String>>()
        response.headers.forEach { name, values -> headers[name.lowercase()] = values.toList() }
        return Written(
            status = response.statusCode?.value() ?: 200,
            headers = headers,
            bytes = bytes.toList(),
            proceeded = called,
            principal = proceeded?.let { "${it.principal.id}@${it.tenant.tenantId}" },
            scope = proceeded?.scope?.let { "${it.appId}/${it.spaceId}/${it.roles}" },
        )
    }

    private fun servlet(scenario: Scenario): Written {
        val request = MockHttpServletRequest(scenario.method, scenario.path.substringBefore('?'))
        request.queryString = scenario.path.substringAfter('?', "").ifEmpty { null }
        request.remoteAddr = "127.0.0.1"
        scenario.headers.forEach { (name, value) -> request.addHeader(name, value) }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        var proceeded: SecurityContext? = null
        val filter = PipelineServletFilter(gates.getValue(scenario.pipeline), contributors)
        filter.doFilter(request, response) { req, _ ->
            proceeded = (req as MockHttpServletRequest).getSecurityContext()
            chain.doFilter(req, response)
        }
        return Written(
            status = response.status,
            headers = response.headerNames.associateTo(
                java.util.TreeMap()
            ) { it.lowercase() to response.getHeaders(it).toList() },
            bytes = response.contentAsByteArray.toList(),
            proceeded = chain.request != null,
            principal = proceeded?.let { "${it.principal.id}@${it.tenant.tenantId}" },
            scope = proceeded?.scope?.let { "${it.appId}/${it.spaceId}/${it.roles}" },
        )
    }

    // --- the scenarios -------------------------------------------------------------------------------------------

    /** One scenario per problem type of §6.1 and per cause of a 503, and the permits. */
    @Suppress("LongMethod")
    fun scenarios(): List<Scenario> {
        val app = mapOf("CoSec-App-Id" to "order")
        fun token(value: String) = app + ("Authorization" to "Bearer $value")
        return listOf(
            Scenario("a global ALLOW permits an anonymous request", path = "/public/page", status = 200, type = null),
            Scenario(
                "a role grant permits",
                path = "/orders/1",
                headers = token("alice@acme:clerk"),
                status = 200,
                type = null
            ),
            Scenario(
                "a request id from the client is answered",
                path = "/public/page",
                headers = mapOf("CoSec-Request-Id" to "client-\"id\""),
                status = 200,
                type = null
            ),
            Scenario("an ambiguous path is 400", path = "/public/%2e%2e/admin", status = 400, type = "invalid-request"),
            Scenario(
                "an ambiguous CoSec parameter is 400",
                path = "/public/page?CoSec-App-Id=other",
                headers = app,
                status = 400,
                type = "invalid-request"
            ),
            Scenario(
                "an anonymous deny is 401",
                path = "/orders/1",
                headers = app,
                status = 401,
                type = "unauthorized"
            ),
            Scenario(
                "an expired token is 401",
                path = "/orders/1",
                headers = token("expired"),
                status = 401,
                type = "token-expired"
            ),
            Scenario(
                "an invalid token is 401",
                path = "/orders/1",
                headers = token("invalid"),
                status = 401,
                type = "token-invalid"
            ),
            Scenario(
                "a revoked token is 401",
                path = "/orders/1",
                headers = token("revoked"),
                status = 401,
                type = "token-revoked"
            ),
            Scenario(
                "an explicit deny is 403",
                method = "DELETE",
                path = "/orders/1",
                headers = token("alice@acme:clerk"),
                status = 403,
                type = "forbidden"
            ),
            Scenario(
                "an implicit deny is the same 403",
                path = "/unknown",
                headers = token("alice@acme:clerk"),
                status = 403,
                type = "forbidden"
            ),
            Scenario(
                "a space without a role is rejected",
                path = "/orders/1",
                headers = token("alice@acme:clerk") + ("CoSec-Space-Id" to "team-x"),
                status = 403,
                type = "scope-rejected"
            ),
            Scenario(
                "an unavailable authenticator is 503",
                path = "/orders/1",
                headers = token("down"),
                status = 503,
                type = "unavailable"
            ),
            Scenario(
                "unavailable memberships are 503",
                path = "/orders/1",
                headers = token("bob@acme:provider"),
                status = 503,
                type = "unavailable"
            ),
            Scenario(
                "an unavailable document is 503",
                path = "/orders/1",
                headers = token("carol@broken:clerk"),
                status = 503,
                type = "unavailable"
            ),
            Scenario(
                "an unavailable external attribute is 503",
                path = "/geo/here",
                status = 503,
                type = "unavailable"
            ),
            Scenario(
                "the deadline is 503",
                path = "/orders/1",
                headers = token("slow"),
                pipeline = "deadline",
                status = 503,
                type = "unavailable"
            ),
            Scenario(
                "a failing REQUIRED listener makes a permit 503",
                path = "/public/page",
                pipeline = "failing-audit",
                status = 503,
                type = "unavailable"
            ),
            Scenario("a defect is 500", path = "/boom", status = 500, type = "internal-error"),
            Scenario(
                "a DENY on a header wins over the global ALLOW",
                path = "/public/page",
                headers = mapOf("X-Debug" to "1"),
                status = 401,
                type = "unauthorized"
            ),
            Scenario(
                "an exhausted rate limit is 429",
                path = "/public/limited/page",
                status = 429,
                type = "too-many-requests"
            ),
        )
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    fun `the three adapters answer byte for byte alike`(scenario: Scenario) {
        events.clear()
        val webflux = webflux(scenario)
        val gateway = gateway(scenario)
        val servlet = servlet(scenario)
        gateway.assert().isEqualTo(webflux)
        servlet.assert().isEqualTo(webflux)

        webflux.status.assert().isEqualTo(scenario.status)
        webflux.requestId.assert().isNotNull()
        if (scenario.type == null) {
            webflux.proceeded.assert().isTrue()
            webflux.body.assert().isEmpty()
            webflux.principal.assert().isNotNull()
        } else {
            webflux.proceeded.assert().isFalse()
            webflux.contentType.assert().isEqualTo("application/problem+json")
            webflux.body.assert().contains("\"type\":\"https://cosec.ahoo.me/problems/${scenario.type}\"")
            webflux.body.assert().contains("\"traceId\":\"${trace.traceId}\"")
        }
        if (scenario.status == 429) {
            webflux.headers["retry-after"].assert().isEqualTo(listOf("3"))
            webflux.headers["ratelimit-remaining"].assert().isEqualTo(listOf("0"))
        }
    }

    /** A service behind a gateway (`cosec.pipeline.mode=gateway`): one scenario per answer of [IdentityGate]. */
    fun identityScenarios(): List<Scenario> {
        val identity = Identity(
            tenant = "acme",
            subject = "alice",
            app = "",
            space = "",
            roles = setOf("clerk"),
            credential = Identity.Credential(Identity.Method.JWT, "t1", null),
            issuedAt = Instant.now(),
            requestId = "r1",
        )
        fun header(value: String) = mapOf(IdentityCodec.HEADER to value)
        return listOf(
            Scenario(
                "the forwarded identity proceeds",
                path = "/orders",
                headers = header(IdentityCodec.encode(identity)),
                pipeline = "identity",
                status = 200,
                type = null
            ),
            Scenario(
                "no identity proceeds as anonymous",
                path = "/orders",
                pipeline = "identity",
                status = 200,
                type = null
            ),
            Scenario(
                "an unreadable identity is a 400",
                path = "/orders",
                headers = header("not base64!"),
                pipeline = "identity",
                status = 400,
                type = "invalid-request"
            ),
            Scenario(
                "an old identity is a 401",
                path = "/orders",
                headers = header(IdentityCodec.encode(identity.copy(issuedAt = Instant.now().minusSeconds(3600)))),
                pipeline = "identity",
                status = 401,
                type = "token-expired",
            ),
        )
    }

    @ParameterizedTest
    @MethodSource("identityScenarios")
    fun `behind a gateway, WebMvc and WebFlux answer byte for byte alike`(scenario: Scenario) {
        val webflux = webflux(scenario)
        val servlet = servlet(scenario)
        servlet.assert().isEqualTo(webflux)
        webflux.status.assert().isEqualTo(scenario.status)
        if (scenario.type == null) {
            webflux.proceeded.assert().isTrue()
            webflux.principal.assert().isNotNull()
        } else {
            webflux.proceeded.assert().isFalse()
            webflux.body.assert().contains("\"type\":\"https://cosec.ahoo.me/problems/${scenario.type}\"")
        }
    }

    /** RFC 0005 §3.2: the three adapters give the engine the same headers, origin and referer. */
    @Test
    fun `the three adapters give the engine the same host attributes`() {
        val headers = mapOf(
            "X-Debug" to "0",
            "Origin" to "https://app.example.com:8443",
            "Referer" to "https://www.example.com/page?q=1",
        )
        val scenario = Scenario("host attributes", path = "/public/page", headers = headers, status = 200, type = null)
        hostAttributes.clear()
        listOf(webflux(scenario), gateway(scenario), servlet(scenario)).forEach { it.status.assert().isEqualTo(200) }
        hostAttributes.assert().containsExactly(
            *Array(3) {
                listOf(
                    "0",
                    "https://app.example.com:8443",
                    "https://www.example.com/page?q=1",
                    "app.example.com",
                    "www.example.com",
                    null
                )
            }
        )
    }

    /** RFC 0006 §5.4.5: the gateway puts the route's id among the request attributes; the other adapters have none. */
    @Test
    fun `the gateway gives the engine the route id`() {
        hostAttributes.clear()
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/public/page"))
        exchange.attributes[GATEWAY_ROUTE_ATTR] =
            Route.async().id("orders-route").uri(URI.create("http://orders"))
                .predicate { true }.build()
        PipelineGatewayFilter(pipelines.getValue("default"), contributors)
            .filter(exchange, GatewayFilterChain { Mono.empty() })
            .block(Duration.ofSeconds(5))
        hostAttributes.single().last().assert().isEqualTo("orders-route")
    }

    /** §6.3: the events of the three adapters agree on what the audit keys on. */
    @ParameterizedTest
    @MethodSource("scenarios")
    fun `the three adapters make the same decision event`(scenario: Scenario) {
        if (scenario.pipeline != "default" || scenario.status == 500) {
            // REQUIRED listeners hear the outcomes the pipeline made in time: not a defect, nor a request past its
            // deadline, and the recorder is not on the failing pipeline.
            return
        }
        val headers = scenario.headers + ("CoSec-Request-Id" to "parity-${scenario.name.hashCode()}")
        val fixed =
            Scenario(
                scenario.name,
                scenario.method,
                scenario.path,
                headers,
                scenario.pipeline,
                scenario.status,
                scenario.type
            )
        events.clear()
        // REQUIRED delivery finishes before the response is written, so each adapter's event is here when it returns.
        listOf(webflux(fixed), gateway(fixed), servlet(fixed)).forEach {
            it.status.assert().describedAs(scenario.name).isEqualTo(scenario.status)
        }
        events.size.assert().isEqualTo(3)
        val keys = events.map {
            listOf(
                it.requestId,
                it.trace?.traceId,
                it.subject.tenant,
                it.subject.id,
                it.subject.authenticated,
                it.scope?.app?.value,
                it.scope?.space?.value,
                it.outcome.status
            )
        }
        keys.distinct().size.assert().isEqualTo(1)
    }

    private companion object {
        /** `http.header.x-debug`, the origin and referer and their hosts, and the gateway's route id. */
        val HOST_ATTRIBUTES = listOf(
            Attribute.of(Attribute.Kind.HTTP_HEADER, "X-Debug"),
            Attribute.of(Attribute.Kind.HTTP_ORIGIN),
            Attribute.of(Attribute.Kind.HTTP_REFERER),
            Attribute.of(Attribute.Kind.HTTP_ORIGIN_HOST),
            Attribute.of(Attribute.Kind.HTTP_REFERER_HOST),
            Attribute.of(Attribute.Kind.REQUEST_ATTRIBUTE, AuditRequest.ROUTE_ID_ATTRIBUTE_KEY),
        )
    }
}
