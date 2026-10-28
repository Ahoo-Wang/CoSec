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

package me.ahoo.cosec.webflux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
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
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.getSecurityContextOrEmpty
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** The WebFlux bridge (RFC 0006 §7.3): writes the outcome it is given, and nothing when the caller goes away. */
class PipelineWebFilterTest {
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

    private fun pipeline(decision: Decision, authenticator: Authenticator = Authenticator { AuthnResult.Anonymous }) =
        Pipeline(
            PipelineComponents(
                authenticator,
                SuspendingSnapshotSource(Views),
                engine = object : FlatEngine {
                    override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel) =
                        FlatEvaluation(decision, null)
                },
            )
        )

    @Test
    fun aPermitProceedsWithItsSecurityContextAndRequestId() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"))
        var seen: Pair<String?, String?>? = null
        val chain = WebFilterChain { authorized ->
            Mono.deferContextual { context ->
                seen = authorized.getSecurityContext()?.scope?.appId to context.getSecurityContextOrEmpty()?.principal?.id
                Mono.empty()
            }
        }
        PipelineWebFilter(pipeline(permit), host = HostScope(AppId("order"))).filter(exchange, chain)
            .block(Duration.ofSeconds(5))
        seen.assert().isEqualTo("order" to "(0)")
        exchange.response.headers.getFirst("CoSec-Request-Id").assert().isNotNull()
    }

    @Test
    fun aDenyWritesTheEncodedProblem() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"))
        PipelineWebFilter(pipeline(Decision.Deny(null))).filter(exchange) { error("never called") }
            .block(Duration.ofSeconds(5))
        exchange.response.statusCode!!.value().assert().isEqualTo(401)
        exchange.response.headers.getFirst("Content-Type").assert().isEqualTo("application/problem+json")
        StepVerifier.create(exchange.response.bodyAsString)
            .assertNext { it.assert().contains("\"code\":\"UNAUTHORIZED\"") }
            .verifyComplete()
    }

    /** §7.4: a client that goes away cancels the request; no outcome exists, so nothing is written. */
    @Test
    fun aCancelledRequestWritesNothing() {
        val reached = CompletableDeferred<Unit>()
        val hanging = Authenticator {
            reached.complete(Unit)
            awaitCancellation()
        }
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"))
        val subscription = PipelineWebFilter(pipeline(permit, hanging)).filter(exchange) { error("never called") }
            .subscribe()
        CompletableFuture.supplyAsync { kotlinx.coroutines.runBlocking { reached.await() } }.get()
        subscription.dispose()
        exchange.response.statusCode.assert().isNull()
        exchange.response.headers.getFirst("CoSec-Request-Id").assert().isNull()
        exchange.response.isCommitted.assert().isFalse()
    }

    @Test
    fun checkMonoAnswersForTheReactiveCaller() {
        val pipeline = pipeline(permit)
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"))
        var context: me.ahoo.cosec.api.context.SecurityContext? = null
        PipelineWebFilter(pipeline).filter(exchange) {
            context = it.getSecurityContext()
            Mono.empty()
        }.block(Duration.ofSeconds(5))
        StepVerifier.create(AuthorizationChecker(pipeline).checkMono(context!!, "http:GET", "/orders/1"))
            .expectNext(true)
            .verifyComplete()
        StepVerifier.create(AuthorizationChecker(pipeline).checkMono(context!!, "GET", "/orders/1"))
            .expectNext(false)
            .verifyComplete()
    }
}
