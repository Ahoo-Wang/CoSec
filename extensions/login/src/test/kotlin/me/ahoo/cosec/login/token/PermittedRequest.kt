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

package me.ahoo.cosec.login.token

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
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.token.TokenSession
import java.time.Instant
import java.util.concurrent.CompletableFuture

/** The context of a request the pipeline permitted, as an adapter hands it to the application: what a switch is asked in. */
object PermittedRequest {
    /**
     * Alice's request with CoSec's own token `t1` of [session], issued at [issuedAt], in acme with [roles] in the
     * default space, for [app].
     */
    fun withToken(
        session: TokenSession?,
        issuedAt: Instant? = Instant.now(),
        principal: String = "alice",
        tenant: String = "acme",
        roles: Set<String> = setOf("admin"),
        attributes: Map<String, Any> = emptyMap(),
        app: String? = null,
    ): SecurityContext = of(
        AuthenticatedPrincipal(
            PrincipalId(principal),
            TenantId(tenant),
            MembershipSource.Claims(
                Memberships(
                    Memberships.Source.CLAIMS,
                    mapOf(SpaceId.DEFAULT to roles.mapTo(LinkedHashSet(), ::RoleId)),
                    emptyList()
                )
            ),
            attributes,
            Identity.Credential(Identity.Method.JWT, "t1", null),
            breakGlass = true,
            issuedAt = issuedAt,
            apiKeyCredential = false,
            app = app?.let(::AppId),
            session = session,
        ),
    )

    /** The request [principal] made, as the pipeline let it through. */
    fun of(principal: AuthenticatedPrincipal): SecurityContext {
        val pipeline = Pipeline.builder(
            Authenticator { AuthnResult.Authenticated(principal, "test") },
            SuspendingSnapshotSource(EMPTY),
        ).engine(PERMITTING).build()
        val outcome = runBlocking { pipeline.evaluate(FACTS) }
        check(outcome.proceed) { "the pipeline did not permit the request: ${outcome.status}" }
        return checkNotNull(outcome.security).securityContext
    }

    private val FACTS = HttpFacts(
        method = "POST",
        rawPath = "/switch",
        rawQuery = null,
        headers = Headers.of(emptyMap()),
        cookies = Cookies.of(emptyMap()),
        remoteAddress = "10.0.0.1",
        host = HostScope(null),
    )

    private val PERMITTING = object : FlatEngine {
        override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel): FlatEvaluation =
            FlatEvaluation(
                Decision.Permit(
                    StatementRef.Policy(PolicyId("open"), 0, "open", BindingRef.Attached(PolicyId("open")))
                ),
                null,
            )
    }

    private val EMPTY = object : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }
}
