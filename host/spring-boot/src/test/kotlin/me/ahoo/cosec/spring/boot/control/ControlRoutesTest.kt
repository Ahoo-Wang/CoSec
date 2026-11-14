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

package me.ahoo.cosec.spring.boot.control

import io.mockk.mockk
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlPlane
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.RequestSecurity
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.webmvc.ServletRequests.setSecurityContext
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.converter.ByteArrayHttpMessageConverter
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.web.servlet.function.ServerRequest as ServletServerRequest

/**
 * RC1-41: on either stack, a control API request no permitted principal stands behind is a 401 before any of its body
 * is read, so an anonymous caller cannot make the host buffer a body. The control plane is never asked either.
 */
class ControlRoutesTest {
    /** Fails on any call: a 401 asks the control plane nothing. */
    private val http = ControlHttp(mockk<ControlPlane>())
    private val properties = ControlProperties()

    @Test
    fun `on WebFlux a request without a permitted principal is a 401, its body never read`() {
        val read = AtomicBoolean()
        val routes = CoSecControlRoutesAutoConfiguration.WebFlux().cosecControlRoutes(http, properties)
        val body = Mono.fromCallable {
            read.set(true)
            BODY
        }
        val request = MockServerRequest.builder()
            .method(HttpMethod.POST)
            .uri(URI.create(PATH))
            .exchange(MockServerWebExchange.from(MockServerHttpRequest.post(PATH)))
            .body(body)
        val handler = routes.route(request).block()!!
        val response = handler.handle(request).block()!!
        response.statusCode().value().assert().isEqualTo(401)
        read.get().assert().isFalse()
    }

    @Test
    fun `on WebMvc a request without a permitted principal is a 401, its body never read`() {
        val read = AtomicBoolean()
        val servlet = object : MockHttpServletRequest("POST", PATH) {
            override fun getInputStream(): ServletInputStream {
                read.set(true)
                return object : ServletInputStream() {
                    override fun read(): Int = -1

                    override fun isFinished(): Boolean = true

                    override fun isReady(): Boolean = true

                    override fun setReadListener(listener: ReadListener?) = Unit
                }
            }
        }
        val routes = CoSecControlRoutesAutoConfiguration.WebMvc().cosecControlRoutes(http, properties)
        val request = ServletServerRequest.create(servlet, listOf(ByteArrayHttpMessageConverter()))
        val response = routes.route(request).get().handle(request)
        response.statusCode().value().assert().isEqualTo(401)
        read.get().assert().isFalse()
    }

    /**
     * Review L1 of #1240: a request the pipeline permitted for no principal, as a deployment that lets `Anyone` onto the
     * control paths does, is a 401 before its body is read too, on both stacks; on WebMvc not even a form body is.
     */
    @Test
    fun `a request the pipeline permitted anonymously is a 401, its body never read`() {
        val anonymous = anonymousPermit()
        anonymous.principal.assert().isNull()
        val read = AtomicBoolean()
        val body = Mono.fromCallable {
            read.set(true)
            BODY
        }
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.post(PATH))
        exchange.setSecurityContext(anonymous.securityContext)
        val flux = MockServerRequest.builder().method(
            HttpMethod.POST
        ).uri(URI.create(PATH)).exchange(exchange).body(body)
        val webFlux = CoSecControlRoutesAutoConfiguration.WebFlux().cosecControlRoutes(http, properties)
        webFlux.route(flux).block()!!.handle(flux).block()!!.statusCode().value().assert().isEqualTo(401)
        read.get().assert().isFalse()

        val servlet = object : MockHttpServletRequest("POST", PATH) {
            override fun getInputStream(): ServletInputStream = throw AssertionError("the body was read")

            override fun getParameterMap(): MutableMap<String, Array<String>> = throw AssertionError(
                "a form body was read"
            )
        }
        servlet.contentType = "application/x-www-form-urlencoded"
        servlet.queryString = "reason=a%20b"
        servlet.setSecurityContext(anonymous.securityContext)
        val mvc = ServletServerRequest.create(servlet, listOf(ByteArrayHttpMessageConverter()))
        CoSecControlRoutesAutoConfiguration.WebMvc().cosecControlRoutes(http, properties).route(mvc).get().handle(mvc)
            .statusCode().value().assert().isEqualTo(401)
    }

    @Test
    fun `the WebMvc query is read from the query string alone`() {
        CoSecControlRoutesAutoConfiguration.queryOf("reason=a%20b+c&limit=5&limit=6&flag&=x&bad=%zz")
            .assert().isEqualTo(mapOf("reason" to "a b c", "limit" to "5", "flag" to "", "" to "x"))
        CoSecControlRoutesAutoConfiguration.queryOf(null).assert().isEmpty()
    }

    /** What the pipeline hands on for a request it permitted for no principal: a permitting engine, no credential. */
    private fun anonymousPermit(): RequestSecurity {
        val views = object : SnapshotViews {
            private fun view(key: ViewKey) = SnapshotView(
                CompiledDocument.platform(emptyList(), emptyList()),
                key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
                key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
            )

            override fun peek(key: ViewKey): SnapshotView = view(key)

            override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(
                view(key)
            )
        }
        val permitting = object : FlatEngine {
            override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel): FlatEvaluation =
                FlatEvaluation(
                    Decision.Permit(
                        StatementRef.Policy(PolicyId("open"), 0, "open", BindingRef.Attached(PolicyId("open")))
                    ),
                    null,
                )
        }
        val pipeline = Pipeline(
            PipelineComponents(
                authenticator = Authenticator { AuthnResult.Anonymous },
                snapshots = SuspendingSnapshotSource(views),
                engine = permitting,
            )
        )
        val facts = HttpFacts(
            method = "POST",
            rawPath = PATH,
            rawQuery = null,
            headers = Headers.of(emptyMap()),
            cookies = Cookies.of(emptyMap()),
            remoteAddress = "10.0.0.1",
            host = HostScope(null),
        )
        return runBlocking { pipeline.evaluate(facts) }.security!!
    }

    private companion object {
        const val PATH = "/cosec/control/v1/simulate"
        val BODY = """{"request":{}}""".encodeToByteArray()
    }
}
