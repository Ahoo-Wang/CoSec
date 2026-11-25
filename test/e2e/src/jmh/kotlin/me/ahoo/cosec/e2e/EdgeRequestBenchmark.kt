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

package me.ahoo.cosec.e2e

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.audit.AuditRequest
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.gateway.IdentityHeadersFilter
import me.ahoo.cosec.gateway.IdentityStripFilter
import me.ahoo.cosec.gateway.PipelineGatewayFilter
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.webflux.HttpFactsReader
import me.ahoo.cosec.webflux.PipelineWebFilter
import me.ahoo.cosec.webflux.ReactiveXForwardedRemoteIpResolver
import me.ahoo.cosec.webmvc.PipelineServletFilter
import me.ahoo.cosec.webmvc.ServletHttpFactsReader
import me.ahoo.cosec.webmvc.ServletXForwardedRemoteIpResolver
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * What a request costs at the edge, around `Pipeline.evaluate` (round 2, R2B-8, R2B-9, R2B-15): the adapters reading a
 * request into [HttpFacts], the bridges, and the gateway's filters, over the slice and documents of [PipelineBenchmark]
 * and the request of [EdgeRequests] (a dozen headers, a bearer token, two cookies, a forwarded-for chain), permitted by
 * the `admin` role, as `PipelineBenchmark`'s `role` request is.
 *
 * Every operation that takes a request makes its own, as a server does: `*Request` is that cost alone, the baseline to
 * subtract from the others of its stack. Shares:
 * - `reactiveRead - reactiveRequest`: the reader (R2B-8); `reactiveReadHeaders`, `reactiveReadCookies`,
 *   `reactiveReadAddress` split it into its three parts, written as `HttpFactsReader` does them today.
 * - `pipelineGateway`, `pipelineHost`: the pipeline alone on the facts read from this request, at a gateway (the
 *   route receives the identity, so it writes `CoSec-Identity`, as `PipelineBenchmark`'s `forwarded`) and at a host.
 * - `gatewayFilters`: what a gateway runs of CoSec for this request: the strip filter, the pipeline filter, and the
 *   last hop's header filter. `gatewayStrip` and `gatewayLastHop` are two of its parts (R2B-9).
 * - `webFilter`: the WebFlux filter at a host.
 * - `servletFilter` against `servletFilterNoReads`, less `servletRequest` against `servletRequestNoReads`: what the
 *   permitted request's wrapper adds to ten header reads of a framework (R2B-15).
 *
 * A warm request never suspends, so every Mono completes on the calling thread.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class EdgeRequestBenchmark {
    private lateinit var slice: Slice
    private lateinit var webFilter: PipelineWebFilter
    private lateinit var gatewayFilter: PipelineGatewayFilter
    private lateinit var servletFilter: PipelineServletFilter
    private lateinit var gatewayFacts: HttpFacts
    private lateinit var hostFacts: HttpFacts
    private lateinit var afterStrip: GatewayFilterChain

    private val strip = IdentityStripFilter()
    private val lastHop = IdentityHeadersFilter()
    private val host = HostScope(AppId("app"))

    /** Behind one load balancer, as `cosec.authorization.client-ip` configures it there. */
    private val reactiveIp = ReactiveXForwardedRemoteIpResolver(maxTrustedIndex = 1)
    private val servletIp = ServletXForwardedRemoteIpResolver(1, true)

    /** A route to a registered service, which receives the identity, with its app in the metadata (RC1-6, RC1-49). */
    private val route: Route = Route.async()
        .id("orders")
        .uri(URI.create("lb://orders"))
        .predicate { true }
        .metadata(PipelineGatewayFilter.APP_METADATA, "app")
        .build()
    private val routeAttributes = mapOf(
        AuditRequest.ROUTE_ID_ATTRIBUTE_KEY to "orders",
        IdentityIssuer.FORWARD_ATTRIBUTE to "true",
    )

    /** What the last stage saw: kept so that nothing it computed is dead. */
    @JvmField
    var sent: HttpHeaders? = null

    @JvmField
    var read: String? = null

    private val webChain = WebFilterChain { Mono.empty() }
    private val gatewayChain = GatewayFilterChain { exchange ->
        // The routing filter applies the header filters to the request it sends: the last hop is one of them.
        sent = lastHop.filter(exchange.request.headers, exchange)
        Mono.empty()
    }
    private val frameworkChain = FilterChain { request, _ -> frameworkReads(request as HttpServletRequest) }
    private val emptyChain = FilterChain { _, _ -> }

    @Setup(Level.Trial)
    fun setup() {
        slice = Slice(
            options = SnapshotOptions(),
            authenticator = CredentialedTokens,
            identityIssuer = IdentityIssuer(),
            meterRegistry = SimpleMeterRegistry(),
        )
        slice.publish(SnapshotKey.Platform, SliceDocuments.platform())
        slice.publish(SnapshotKey.App("app"), SliceDocuments.app())
        slice.publish(SnapshotKey.Tenant("acme"), "")
        webFilter = PipelineWebFilter(slice.pipeline, remoteIpResolver = reactiveIp, host = host)
        gatewayFilter = PipelineGatewayFilter(slice.pipeline, remoteIpResolver = reactiveIp)
        servletFilter = PipelineServletFilter(slice.pipeline, remoteIpResolver = servletIp, host = host)
        afterStrip = GatewayFilterChain { exchange -> gatewayFilter.filter(exchange, gatewayChain) }

        gatewayFacts = reactiveRead()
        hostFacts = HttpFactsReader.read(EdgeRequests.exchange(), reactiveIp, host)
        val atGateway = runBlocking { slice.evaluate(gatewayFacts) }
        check(atGateway.proceed && atGateway.forward[IdentityCodec.HEADER] != null) { "At the gateway: $atGateway" }
        val atHost = runBlocking { slice.evaluate(hostFacts) }
        check(atHost.proceed && atHost.forward[IdentityCodec.HEADER] == null) { "At the host: $atHost" }
        check(gatewayFacts.remoteAddress == "10.0.0.2") { "The client address: ${gatewayFacts.remoteAddress}" }
        check(servletRead().remoteAddress == gatewayFacts.remoteAddress)

        check(strip.filter(gatewayExchange(), afterStrip).toFuture().isDone) { "A warm request completes at once." }
        val forwarded = checkNotNull(sent) { "The gateway did not forward the request." }
        check(forwarded.containsHeader(IdentityCodec.HEADER) && !forwarded.containsHeader(HttpHeaders.AUTHORIZATION)) {
            "The gateway forwards the identity and not the token: $forwarded"
        }
        check(webFilter.filter(EdgeRequests.exchange(), webChain).toFuture().isDone)
        val response = MockHttpServletResponse()
        read = null
        servletFilter.doFilter(EdgeRequests.servletRequest(), response, frameworkChain)
        check(response.status == 200 && read != null) { "The servlet filter: ${response.status}" }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        slice.close()
    }

    private fun gatewayExchange(): ServerWebExchange =
        EdgeRequests.exchange().also { it.attributes[GATEWAY_ROUTE_ATTR] = route }

    private fun evaluate(facts: HttpFacts): Outcome {
        val result = CompletableFuture<Outcome>()
        val call: suspend () -> Outcome = { slice.evaluate(facts) }
        call.startCoroutine(Continuation(EmptyCoroutineContext) { result.complete(it.getOrThrow()) })
        return result.join()
    }

    private fun frameworkReads(request: HttpServletRequest) {
        for (name in EdgeRequests.FRAMEWORK_READS) {
            read = request.getHeader(name) ?: read
        }
    }

    @Benchmark
    fun reactiveRequest(): ServerWebExchange = gatewayExchange()

    @Benchmark
    fun reactiveRead(): HttpFacts = HttpFactsReader.read(gatewayExchange(), reactiveIp, HttpFactsReader.NO_HOST, routeAttributes, "app")

    /** The headers' part of [reactiveRead], as `HttpFactsReader` does it: a copy, then `Headers.of`'s folded copy. */
    @Benchmark
    fun reactiveReadHeaders(): Headers {
        val request = gatewayExchange().request
        val headers = HashMap<String, List<String>>(request.headers.size())
        request.headers.forEach { name, values -> headers[name] = values }
        return Headers.of(headers)
    }

    /** The cookies' part of [reactiveRead]: the server's parse on first read, then two copies. */
    @Benchmark
    fun reactiveReadCookies(): Cookies =
        Cookies.of(gatewayExchange().request.cookies.mapValues { (_, values) -> values.map { it.value } })

    /** The client address's part of [reactiveRead]. */
    @Benchmark
    fun reactiveReadAddress(): String = reactiveIp.resolve(gatewayExchange())

    @Benchmark
    fun pipelineGateway(): Outcome = evaluate(gatewayFacts)

    @Benchmark
    fun pipelineHost(): Outcome = evaluate(hostFacts)

    @Benchmark
    fun gatewayFilters(): HttpHeaders? {
        strip.filter(gatewayExchange(), afterStrip).toFuture().join()
        return sent
    }

    /** The pipeline filter's first step, which `IdentityStripFilter` has already done for this request (R2B-9). */
    @Benchmark
    fun gatewayStrip(): ServerWebExchange =
        gatewayExchange().mutate().request { it.headers { headers -> headers.remove(IdentityCodec.HEADER) } }.build()

    /** The last hop for a request that forwards an identity: a copy of every header (R2B-9). */
    @Benchmark
    fun gatewayLastHop(): HttpHeaders {
        val exchange = gatewayExchange()
        exchange.attributes[IdentityHeadersFilter.FORWARDED_ATTRIBUTE] = FORWARDED
        return lastHop.filter(exchange.request.headers, exchange)
    }

    @Benchmark
    fun webFilter() {
        webFilter.filter(EdgeRequests.exchange(), webChain).toFuture().join()
    }

    @Benchmark
    fun servletRequest(): String? {
        frameworkChain.doFilter(EdgeRequests.servletRequest(), MockHttpServletResponse())
        return read
    }

    @Benchmark
    fun servletRequestNoReads(blackhole: Blackhole) {
        blackhole.consume(EdgeRequests.servletRequest())
        blackhole.consume(MockHttpServletResponse())
    }

    @Benchmark
    fun servletRead(): HttpFacts = ServletHttpFactsReader.read(EdgeRequests.servletRequest(), servletIp, host)

    @Benchmark
    fun servletFilter(): String? {
        servletFilter.doFilter(EdgeRequests.servletRequest(), MockHttpServletResponse(), frameworkChain)
        return read
    }

    @Benchmark
    fun servletFilterNoReads() {
        servletFilter.doFilter(EdgeRequests.servletRequest(), MockHttpServletResponse(), emptyChain)
    }

    private companion object {
        /** A forwarded identity's length, about: the last hop copies the headers whatever the value. */
        val FORWARDED = "x".repeat(256)
    }
}
