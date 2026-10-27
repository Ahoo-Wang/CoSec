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

package me.ahoo.cosec.control.authz

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.membership.MembershipClaims
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.authn.AuthenticatedPrincipal
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDocumentCompiler
import java.util.concurrent.CompletableFuture

/**
 * The control plane's authorization as a host wires it (RFC 0007 §7.2), with the real pipeline and engine: a host
 * pipeline authenticates the operator and permits the control API's path, and a second pipeline, over the current
 * heads of [revisions] (always current, so nothing to confirm), answers the [PipelineControlAuthorizer]'s checks.
 */
class EngineAuthority(private val revisions: Revisions) {
    private val checker = AuthorizationChecker(
        Pipeline(
            PipelineComponents(
                authenticator = Authenticator {
                    AuthnResult.Anonymous
                },
                snapshots = SuspendingSnapshotSource(HeadViews(revisions))
            )
        ),
    )

    var confirmation: DocumentConfirmation = DocumentConfirmation.CURRENT

    val authorizer: ControlAuthorizer =
        PipelineControlAuthorizer(checker, { keys -> confirmation.confirm(keys) })

    /** An operator of [tenant], with `role@space` [roles] (a bare role is the default space's) and attached [policies]. */
    fun operator(
        tenant: TenantId,
        id: String,
        roles: List<String> = emptyList(),
        policies: List<String> = emptyList(),
        attributes: Map<String, String> = emptyMap(),
    ): Operator {
        val principal =
            AuthenticatedPrincipal(PrincipalId(id), tenant, MembershipClaims.parse(roles, policies), attributes)
        val host = Pipeline(
            PipelineComponents(
                authenticator = Authenticator { AuthnResult.Authenticated(principal, "test") },
                snapshots = SuspendingSnapshotSource(HOST_VIEWS),
                engine = PERMITTING,
            )
        )
        val outcome = runBlocking { host.evaluate(FACTS) }
        check(outcome.proceed) { "The host pipeline did not permit the control API: ${outcome.status}" }
        return Operator.of(outcome.security!!)!!
    }

    /** Compiles the current heads on every load, as a source that confirms every document before a check would. */
    private class HeadViews(private val revisions: Revisions) : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView? = null

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(
            runBlocking {
                view(key)
            }
        )

        private suspend fun view(key: ViewKey): SnapshotView {
            val platform = revisions.head(SnapshotKey.Platform)?.document
            val compiler = PolicyDocumentCompiler.forPlatform(platform)
            return SnapshotView(
                platform?.let { compiler.compile(it) } ?: CompiledDocument.platform(emptyList(), emptyList()),
                key.app?.let { app ->
                    revisions.head(SnapshotKey.App(app.value))?.document?.let { compiler.compile(it) }
                        ?: CompiledDocument.app(app, emptyList(), emptyList())
                },
                key.tenant?.let { tenant ->
                    revisions.head(SnapshotKey.Tenant(tenant.value))?.document?.let { compiler.compile(it) }
                        ?: CompiledDocument.tenant(tenant, emptyList(), emptyList())
                },
            )
        }
    }

    private companion object {
        val FACTS = HttpFacts(
            method = "POST",
            rawPath = "/cosec/control/v1/changes",
            rawQuery = null,
            headers = Headers.of(emptyMap()),
            cookies = Cookies.of(emptyMap()),
            remoteAddress = "10.0.0.1",
            host = HostScope(null),
        )

        /** The host's coarse rule, "the control API is open to authenticated principals", stands in as a permit. */
        val PERMITTING = object : FlatEngine {
            override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel): FlatEvaluation =
                FlatEvaluation(
                    Decision.Permit(
                        StatementRef.Policy(
                            PolicyId("control-api"),
                            0,
                            "open",
                            BindingRef.Attached(PolicyId("control-api"))
                        )
                    ),
                    null,
                )
        }

        val HOST_VIEWS = object : SnapshotViews {
            private val view = { key: ViewKey ->
                SnapshotView(
                    CompiledDocument.platform(emptyList(), emptyList()),
                    key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
                    key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
                )
            }

            override fun peek(key: ViewKey): SnapshotView = view(key)

            override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(
                view(key)
            )
        }
    }
}
