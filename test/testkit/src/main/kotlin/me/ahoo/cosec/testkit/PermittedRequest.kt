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

package me.ahoo.cosec.testkit

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
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
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenVerifier
import java.util.concurrent.CompletableFuture

/**
 * The [SecurityContext] of a request a token would be permitted on, for testing what an application does with a
 * context (a tenant switch, a handler that reads it); not a way to decide anything, for which [PipelineFixture] and
 * [PolicyTestKit] exist.
 *
 * The context is made as in production: CoSec's own token authenticator verifies the token on a request that carries it
 * in `Authorization`, and reads its [revocations], so what the context carries by provenance (the principal, its tenant
 * and app, and with CoSec's own [TokenVerifier] the token's session, which a tenant switch issues from) is what the
 * token says. Only the host's rule is a stand-in: it permits the request, whatever the documents would say.
 */
@ExperimentalCoSecApi
object PermittedRequest {
    /**
     * The context of the request [token] makes, verified by [verifier] over [revocations].
     *
     * @throws IllegalArgumentException when [verifier] refuses [token] (invalid, expired, revoked), so a test never
     * goes on with an anonymous context it did not ask for
     */
    fun withToken(
        token: String,
        verifier: TokenVerifier,
        revocations: RevocationReader = RevocationReader.NONE
    ): SecurityContext {
        val pipeline = Pipeline.builder(
            TokenAuthenticator(verifier, revocations),
            SuspendingSnapshotSource(NO_DOCUMENTS)
        )
            .engine(PERMITTING)
            .build()
        val facts = HttpFacts(
            method = "POST",
            rawPath = "/",
            rawQuery = null,
            headers = Headers.of(mapOf("Authorization" to listOf("Bearer $token"))),
            cookies = Cookies.of(emptyMap()),
            remoteAddress = "127.0.0.1",
            host = HostScope(null),
        )
        val outcome = runBlocking { pipeline.evaluate(facts) }
        val security = checkNotNull(outcome.security) { "The request was not permitted: ${outcome.status}." }
        val context = security.securityContext
        require(context.principal.authenticated) { "The verifier refused the token: invalid, expired or revoked." }
        return context
    }

    /** The host's rule, "permit the request": the context is what is tested, not the decision. */
    private val PERMITTING = object : FlatEngine {
        override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel): FlatEvaluation =
            FlatEvaluation(Decision.Permit(StatementRef.Policy(PERMIT, 0, "", BindingRef.Attached(PERMIT))), null)
    }

    private val PERMIT = PolicyId("testkit-permitted-request")

    private val NO_DOCUMENTS = object : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }
}
