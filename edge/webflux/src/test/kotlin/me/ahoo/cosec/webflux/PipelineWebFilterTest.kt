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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.webflux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.Engine
import me.ahoo.cosec.engine.EngineRequest
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.engine.TraceLevel
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
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.securityContextOrNull
import me.ahoo.cosec.webflux.ServerWebExchanges.securityContextOrNull
import me.ahoo.cosec.spi.identity.Identity
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
        Pipeline.builder(authenticator, SuspendingSnapshotSource(Views))
            .engine(object : Engine {
                override fun evaluate(request: EngineRequest, snapshot: SnapshotView, trace: TraceLevel) =
                    Evaluation(decision, null)
            })
            .build()

    @Test
    fun aPermitProceedsWithItsSecurityContextAndRequestId() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"))
        var seen: Pair<String?, String?>? = null
        val chain = WebFilterChain { authorized ->
            Mono.deferContextual { context ->
                seen = authorized.securityContextOrNull()?.scope?.appId to context.securityContextOrNull()?.principal?.id
                Mono.empty()
            }
        }
        PipelineWebFilter(pipeline(permit), host = HostScope(AppId("order"))).filter(exchange, chain)
            .block(Duration.ofSeconds(5))
        seen.assert().isEqualTo("order" to "(0)")
        exchange.response.headers.getFirst("CoSec-Request-Id").assert().isNotNull()
    }

    /**
     * UA-8: the request's `Principal` is set for an authenticated caller only, as the servlet containers set theirs, so a
     * handler that reads "a principal is present" as "signed in" never counts anonymous callers as one user. The
     * anonymous principal stays in the security context.
     */
    @Test
    fun anAnonymousPermitHasNoRequestPrincipal() {
        fun principalOf(authenticator: Authenticator): Pair<String?, String?> {
            var seen: Pair<String?, String?>? = null
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"))
            PipelineWebFilter(pipeline(permit, authenticator)).filter(exchange) { authorized ->
                authorized.getPrincipal<java.security.Principal>().map { it.name }.defaultIfEmpty("none")
                    .doOnNext { seen = it to authorized.securityContextOrNull()?.principal?.id }
                    .then()
            }.block(Duration.ofSeconds(5))
            return seen!!
        }
        principalOf(Authenticator { AuthnResult.Anonymous }).assert().isEqualTo("none" to "(0)")
        val alice = AuthenticatedPrincipal(
            PrincipalId("alice"),
            TenantId("acme"),
            MembershipSource.Claims(Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())),
        )
        principalOf(Authenticator { AuthnResult.Authenticated(alice, Identity.Method("test")) }).assert().isEqualTo("alice" to "alice")
    }

    /**
     * `PIP-28` with a filter in front (Spring Security) that set its own principal: CoSec's replaces it on a request
     * CoSec authenticated; on one CoSec treats as anonymous, the other layer's is left as it was.
     */
    @Test
    fun anotherLayersPrincipalIsReplacedOnlyWhenCoSecAuthenticated() {
        fun principalOf(authenticator: Authenticator): String? {
            var seen: String? = null
            val front = MockServerWebExchange.builder(MockServerHttpRequest.get("/orders"))
                .principal(java.security.Principal { "bob" })
                .build()
            PipelineWebFilter(pipeline(permit, authenticator)).filter(front) { authorized ->
                authorized.getPrincipal<java.security.Principal>().doOnNext { seen = it.name }.then()
            }.block(Duration.ofSeconds(5))
            return seen
        }
        principalOf(Authenticator { AuthnResult.Anonymous }).assert().isEqualTo("bob")
        val alice = AuthenticatedPrincipal(
            PrincipalId("alice"),
            TenantId("acme"),
            MembershipSource.Claims(Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())),
        )
        principalOf(Authenticator { AuthnResult.Authenticated(alice, Identity.Method("test")) }).assert().isEqualTo("alice")
    }

    /** IDN-9, RC1-18: the handler of an enforcing host never sees a `CoSec-Identity` a client sent. */
    @Test
    fun aPermitHandsOnNoClientIdentity() {
        val forged = MockServerWebExchange.from(
            MockServerHttpRequest.get("/orders").header("CoSec-Identity", "forged").header("X-Other", "kept")
        )
        var handed: org.springframework.http.HttpHeaders? = null
        PipelineWebFilter(pipeline(permit)).filter(forged) { authorized ->
            handed = authorized.request.headers
            Mono.empty()
        }.block(Duration.ofSeconds(5))
        handed!!.containsHeader("CoSec-Identity").assert().isFalse()
        handed!!.getFirst("X-Other").assert().isEqualTo("kept")
        // Without one, the request is handed on as it came: nothing to copy.
        val plain = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"))
        var request: org.springframework.http.server.reactive.ServerHttpRequest? = null
        PipelineWebFilter(pipeline(permit)).filter(plain) { authorized ->
            request = authorized.request
            Mono.empty()
        }.block(Duration.ofSeconds(5))
        request.assert().isSameAs(plain.request)
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
        val checker = AuthorizationChecker(pipeline)
        var answers: List<Boolean>? = null
        // Within the request: once it has ended, a check of it is denied (PIP-26, RequestEndTest).
        PipelineWebFilter(pipeline).filter(exchange) {
            val context = it.securityContextOrNull()!!
            checker.checkMono(context, "http:GET", "/orders/1")
                .zipWith(checker.checkMono(context, "GET", "/orders/1")) { a, b -> listOf(a, b) }
                .doOnNext { both -> answers = both }
                .then()
        }.block(Duration.ofSeconds(5))
        answers.assert().isEqualTo(listOf(true, false))
    }
}
