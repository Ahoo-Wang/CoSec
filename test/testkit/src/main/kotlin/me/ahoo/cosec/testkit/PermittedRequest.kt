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
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.Engine
import me.ahoo.cosec.engine.EngineRequest
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.engine.TraceLevel
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
 *
 * The request is the pipeline's, so a test can give it the headers a client would send, and what the pipeline reads
 * from them is what the context answers: a `CoSec-Device-Id` is the context's `deviceId`, and two different values are
 * as ambiguous here as on a host.
 */
@ExperimentalCoSecApi
object PermittedRequest {
    /**
     * The context of the request [token] makes, verified by [verifier] over [revocations].
     *
     * [headers] are the request's other headers, such as `CoSec-Device-Id`; names are case-insensitive. The token is the
     * request's credential and no header can replace it: `Authorization`, `X-API-Key` and `CoSec-Identity` are refused.
     *
     * @throws IllegalArgumentException when [verifier] refuses [token] (invalid, expired, revoked), so a test never
     * goes on with an anonymous context it did not ask for, or when [headers] names a credential header
     * @throws IllegalStateException when the pipeline does not permit the request: a header it refuses, such as a
     * `CoSec-Device-Id` sent with two different values (`400`), as it would on a host
     */
    fun withToken(
        token: String,
        verifier: TokenVerifier,
        revocations: RevocationReader = RevocationReader.NONE,
        headers: Map<String, List<String>> = emptyMap(),
    ): SecurityContext {
        headers.keys.firstOrNull { name -> CREDENTIAL_HEADERS.any { it.equals(name, ignoreCase = true) } }?.let {
            throw IllegalArgumentException(
                "The header [$it] carries a credential, and the credential of this request is the token: " +
                    "pass the token as [token], not through [headers]."
            )
        }
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
            headers = Headers.of(headers + ("Authorization" to listOf("Bearer $token"))),
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

    /** The headers that carry who the caller is: a test passes the token, never one of these. */
    private val CREDENTIAL_HEADERS = listOf("Authorization", "X-API-Key", "CoSec-Identity")

    /** The host's rule, "permit the request": the context is what is tested, not the decision. */
    private val PERMITTING = object : Engine {
        override fun evaluate(request: EngineRequest, snapshot: SnapshotView, trace: TraceLevel): Evaluation =
            Evaluation(Decision.Permit(StatementRef.Policy(PERMIT, 0, "", BindingRef.Attached(PERMIT))), null)
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
