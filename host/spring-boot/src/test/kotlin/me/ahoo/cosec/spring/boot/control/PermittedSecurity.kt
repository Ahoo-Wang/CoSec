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

package me.ahoo.cosec.spring.boot.control

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.Engine
import me.ahoo.cosec.engine.EngineRequest
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.membership.MembershipClaims
import java.util.concurrent.CompletableFuture

/** What the pipeline hands on for a request to [path] it permitted for alice of acme: a permitting engine, her token. */
internal fun permittedSecurity(path: String): RequestSecurity {
    val principal = AuthenticatedPrincipal(
        PrincipalId("alice"),
        TenantId("acme"),
        MembershipClaims.parse(emptyList(), emptyList()),
        emptyMap(),
        Identity.Credential(Identity.Method("test"), "token-alice", null),
    )
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
    val permitting = object : Engine {
        override fun evaluate(request: EngineRequest, snapshot: SnapshotView, trace: TraceLevel): Evaluation =
            Evaluation(
                Decision.Permit(
                    StatementRef.Policy(PolicyId("open"), 0, "open", BindingRef.Attached(PolicyId("open")))
                ),
                null,
            )
    }
    val pipeline = Pipeline.builder(
        Authenticator { AuthnResult.Authenticated(principal, Identity.Method("test")) },
        SuspendingSnapshotSource(views),
    )
        .engine(permitting)
        .build()
    val facts = HttpFacts(
        method = "GET",
        rawPath = path,
        rawQuery = null,
        headers = Headers.of(emptyMap()),
        cookies = Cookies.of(emptyMap()),
        remoteAddress = "10.0.0.1",
        host = HostScope(null),
    )
    return runBlocking { pipeline.evaluate(facts) }.security!!
}
