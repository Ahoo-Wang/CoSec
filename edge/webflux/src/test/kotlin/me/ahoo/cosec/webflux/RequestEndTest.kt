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

import io.mockk.mockk
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.Engine
import me.ahoo.cosec.engine.EngineRequest
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.getSecurityContext
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.getSecurityContextOrEmpty
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.context.Context
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * `PIP-26`: the WebFlux adapter (and the gateway, which shares its bridge) ends a request when the chain's `Mono`
 * terminates. A check during a streamed body, however late, is answered; one after the body is written, or after the
 * client went away, is denied. `PIP-27`: the readers answer only a context the pipeline made.
 */
class RequestEndTest {
    private object Views : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    private val pipeline: Pipeline = Pipeline
        .builder(Authenticator { AuthnResult.Anonymous }, SuspendingSnapshotSource(Views))
        .engine(object : Engine {
            override fun evaluate(request: EngineRequest, snapshot: SnapshotView, trace: TraceLevel) = Evaluation(
                Decision.Permit(
                    StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Bound(BindingId("g"), DocumentKind.PLATFORM))
                ),
                null,
            )
        })
        .build()

    private val checker = AuthorizationChecker(pipeline)
    private val filter = PipelineWebFilter(pipeline)

    private fun check(context: SecurityContext): Boolean = checker.checkMono(context, "order:read", "order/1")
        .block(Duration.ofSeconds(5))!!

    @Test
    fun `a check while a streamed body is written is answered, and one after it is denied`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders/stream"))
        val kept = AtomicReference<SecurityContext>()
        val during = CopyOnWriteArrayList<Boolean>()
        val onCompletion = AtomicReference<Boolean>()
        filter.filter(exchange) { authorized ->
            Mono.deferContextual { reactor ->
                val context = reactor.getSecurityContext()
                kept.set(context)
                // An event stream: each element is written after a delay, and checks as it goes.
                val body = Flux.interval(Duration.ofMillis(50)).take(3)
                    .concatMap { checker.checkMono(context, "order:read", "order/1").doOnNext(during::add) }
                    .map { authorized.response.bufferFactory().wrap("data: $it\n\n".toByteArray()) }
                authorized.response.writeWith(body)
            }
        }.doOnSuccess { onCompletion.set(runBlocking { checker.check(kept.get(), "order:read", "order/1") }) }
            .block(Duration.ofSeconds(5))
        during.assert().containsExactly(true, true, true)
        // Made as the completion reaches the filter's subscriber, on the thread that completed the body: the bridge
        // ended the request before passing the completion on, so no one sees the chain complete with it in progress.
        onCompletion.get().assert().isEqualTo(false)
        check(kept.get()).assert().isFalse()
        mono { checker.decide(kept.get(), "order:read", "order/1") }.block().assert().isEqualTo(Decision.Deny(null))
    }

    @Test
    fun `a check after the client went away is denied`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders/stream"))
        val kept = AtomicReference<SecurityContext>()
        val reached = CountDownLatch(1)
        val subscription = filter.filter(exchange) { authorized ->
            kept.set(authorized.getSecurityContext())
            reached.countDown()
            Mono.never()
        }.subscribe()
        reached.await(5, TimeUnit.SECONDS).assert().isTrue()
        check(kept.get()).assert().isTrue()
        subscription.dispose()
        check(kept.get()).assert().isFalse()
    }

    @Test
    fun `a context the pipeline did not make reads as none`() {
        val foreign = mockk<SecurityContext>()
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"))
        exchange.attributes[SecurityContext.KEY] = foreign
        exchange.getSecurityContext().assert().isNull()
        exchange.attributes[SecurityContext.KEY] = "not a context"
        exchange.getSecurityContext().assert().isNull()

        val reactor = Context.of(SecurityContext.KEY, foreign)
        reactor.getSecurityContextOrEmpty().assert().isNull()
        assertThrows<IllegalStateException> { reactor.getSecurityContext() }

        // The one the adapter binds is read, from both.
        val seen = AtomicReference<Pair<SecurityContext?, SecurityContext?>>()
        filter.filter(exchange) { authorized ->
            Mono.deferContextual { context ->
                seen.set(authorized.getSecurityContext() to context.getSecurityContextOrEmpty())
                Mono.empty()
            }
        }.block(Duration.ofSeconds(5))
        seen.get().first.assert().isNotNull()
        seen.get().second.assert().isSameAs(seen.get().first)
    }
}
