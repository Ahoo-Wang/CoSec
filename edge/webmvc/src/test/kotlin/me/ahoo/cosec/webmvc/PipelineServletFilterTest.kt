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

package me.ahoo.cosec.webmvc

import jakarta.servlet.http.HttpServletRequest
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.webmvc.ServletRequests.getSecurityContext
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Collections
import java.util.concurrent.CompletableFuture

/** The servlet bridge (RFC 0006 §7.3): writes the outcome it is given and always clears the holder. */
class PipelineServletFilterTest {
    private object Views : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    private val permit = Decision.Permit(
        StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Bound(BindingId("g"), DocumentKind.PLATFORM))
    )

    private fun pipeline(decision: Decision) = Pipeline(
        PipelineComponents(
            Authenticator { AuthnResult.Anonymous },
            SuspendingSnapshotSource(Views),
            engine = object : FlatEngine {
                override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel) =
                    FlatEvaluation(decision, null)
            },
        )
    )

    @Test
    fun aPermitProceedsBoundToTheHolderUntilTheRequestCompletes() {
        val pipeline = pipeline(permit)
        val request = MockHttpServletRequest("GET", "/orders")
        val response = MockHttpServletResponse()
        var bound: SecurityContext? = null
        var fromRequest: SecurityContext? = null
        PipelineServletFilter(pipeline, host = HostScope(AppId("order"))).doFilter(request, response) { req, _ ->
            bound = SecurityContextHolder.requiredContext
            fromRequest = (req as MockHttpServletRequest).getSecurityContext()
        }
        bound!!.scope.appId.assert().isEqualTo("order")
        fromRequest.assert().isSameAs(bound)
        SecurityContextHolder.context.assert().isNull()
        response.getHeader("CoSec-Request-Id").assert().isNotNull()
        AuthorizationChecker(pipeline).checkBlocking(bound!!, "http:GET", "/orders/1").assert().isTrue()
        AuthorizationChecker(pipeline).checkBlocking(bound!!, "GET", "/orders/1").assert().isFalse()
        // Never on a non-blocking thread: there it would stall an event loop.
        val context = bound!!
        val onEventLoop = java.util.concurrent.CompletableFuture<Throwable?>()
        reactor.core.scheduler.Schedulers.parallel().schedule {
            onEventLoop.complete(
                runCatching {
                    AuthorizationChecker(
                        pipeline
                    ).checkBlocking(context, "http:GET", "/o")
                }.exceptionOrNull()
            )
        }
        onEventLoop.get().assert().isInstanceOf(IllegalStateException::class.java)
    }

    /** IDN-9, RC1-18: the handler of an enforcing host never sees a `CoSec-Identity` a client sent. */
    @Test
    fun aPermitHandsOnNoClientIdentity() {
        val request = MockHttpServletRequest("GET", "/orders")
        request.addHeader("CoSec-Identity", "forged")
        request.addHeader("X-Other", "kept")
        var handed: HttpServletRequest? = null
        PipelineServletFilter(pipeline(permit)).doFilter(request, MockHttpServletResponse()) { req, _ ->
            handed = req as HttpServletRequest
        }
        val seen = handed!!
        seen.getHeader("cosec-identity").assert().isNull()
        Collections.list(seen.getHeaders("CoSec-Identity")).assert().isEmpty()
        Collections.list(seen.headerNames).map { it.lowercase() }.assert().contains("x-other")
            .doesNotContain("cosec-identity")
        seen.getHeader("X-Other").assert().isEqualTo("kept")
        seen.getIntHeader("CoSec-Identity").assert().isEqualTo(-1)
        seen.getDateHeader("CoSec-Identity").assert().isEqualTo(-1)
        seen.getSecurityContext().assert().isNotNull()
        // Without one, the request is handed on as it came: nothing to wrap.
        val plain = MockHttpServletRequest("GET", "/orders")
        PipelineServletFilter(pipeline(permit)).doFilter(plain, MockHttpServletResponse()) { req, _ ->
            handed = req as HttpServletRequest
        }
        handed.assert().isSameAs(plain)
    }

    /** What the wrapper answers for a header the gate sets, which no gate of CoSec does on a servlet host today. */
    @Test
    fun aForwardedValueReplacesTheClients() {
        val request = MockHttpServletRequest("GET", "/orders")
        request.addHeader("X-Set", "client")
        request.addHeader("X-Number", "1")
        val wrapped = ForwardedHeadersRequest.of(request, mapOf("x-set" to "gate", "X-Number" to "2", "X-New" to "n"))
        wrapped.getHeader("X-Set").assert().isEqualTo("gate")
        Collections.list(wrapped.getHeaders("X-Set")).assert().containsExactly("gate")
        wrapped.getIntHeader("x-number").assert().isEqualTo(2)
        wrapped.getHeader("X-New").assert().isEqualTo("n")
        Collections.list(wrapped.headerNames).map { it.lowercase() }.sorted().assert()
            .containsExactly("x-new", "x-number", "x-set")
        assertThrownBy<IllegalArgumentException> { wrapped.getDateHeader("X-Set") }
        wrapped.getHeader("X-Absent").assert().isNull()
    }

    @Test
    fun aDenyWritesTheEncodedProblemAndStops() {
        val response = MockHttpServletResponse()
        var called = false
        PipelineServletFilter(pipeline(Decision.Deny(null)))
            .doFilter(MockHttpServletRequest("GET", "/orders?a=1"), response) { _, _ -> called = true }
        called.assert().isFalse()
        response.status.assert().isEqualTo(401)
        response.contentType.assert().isEqualTo("application/problem+json")
        response.contentAsString.assert().contains("\"code\":\"UNAUTHORIZED\"")
    }
}
