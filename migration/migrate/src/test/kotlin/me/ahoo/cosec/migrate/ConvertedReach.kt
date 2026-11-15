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

package me.ahoo.cosec.migrate

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.ControlResources
import me.ahoo.cosec.control.authz.DocumentConfirmation
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.AuthorizationChecker
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
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.util.concurrent.CompletableFuture

/**
 * What converted documents let a principal do, decided as a host decides it (MIG-2): the documents compiled into a
 * real pipeline, the control plane's own checks through [PipelineControlAuthorizer], a check in code through the
 * [AuthorizationChecker]. The principal's request comes from a host that permits the control API, as a host's coarse
 * rule does, so what is asked here is only what the converted documents grant.
 */
class ConvertedReach(documents: Map<SnapshotKey, PolicyDocument>) {
    private val checker = AuthorizationChecker(
        Pipeline.builder(Authenticator { AuthnResult.Anonymous }, SuspendingSnapshotSource(Views(documents))).build(),
    )

    /** The control plane's checks over the converted documents, as a host wires them for `Changes`. */
    val authorizer = PipelineControlAuthorizer(checker, DocumentConfirmation.CURRENT)

    /** The control-plane actions [security]'s operator is permitted on [key], as `cosec:<verb>`. */
    fun controlActions(security: RequestSecurity, key: SnapshotKey): List<String> {
        val operator = checkNotNull(Operator.of(security))
        return CONTROL_ACTIONS.filter { action ->
            runBlocking { authorizer.decide(operator, action, ControlResources.document(key)) } == ControlDecision.PERMIT
        }.map { "${it.namespace}:${it.verb}" }
    }

    /** Whether a check in code of [action] on [resource] is permitted, in the app [security]'s credential declares. */
    fun permits(security: RequestSecurity, action: Action, resource: Resource): Boolean =
        runBlocking { checker.check(security, action, resource) }

    /** The converted documents, compiled, as a snapshot source holds them once installed. */
    private class Views(documents: Map<SnapshotKey, PolicyDocument>) : SnapshotViews {
        private val compiler = PolicyDocumentCompiler.forPlatform(documents[SnapshotKey.Platform])
        private val compiled = documents.mapValues { (_, document) -> compiler.compile(document) }

        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            compiled[SnapshotKey.Platform] ?: CompiledDocument.platform(emptyList(), emptyList()),
            key.app?.let { app -> compiled[SnapshotKey.App(app.value)] ?: CompiledDocument.app(app, emptyList(), emptyList()) },
            key.tenant?.let { tenant ->
                compiled[SnapshotKey.Tenant(tenant.value)] ?: CompiledDocument.tenant(tenant, emptyList(), emptyList())
            },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    companion object {
        /** Every control-plane action (RFC 0007 §7.1). */
        val CONTROL_ACTIONS: List<Action> = listOf(
            ControlActions.READ,
            ControlActions.SIMULATE,
            ControlActions.EXPLAIN,
            ControlActions.ANALYZE,
            ControlActions.SHADOW_READ,
            ControlActions.WRITE,
            ControlActions.GRANT,
            ControlActions.PUBLISH,
            ControlActions.SKIP_SHADOW,
            ControlActions.APPROVE,
            ControlActions.UNPROTECT,
            ControlActions.BREAKGLASS,
            ControlActions.BOUNDARY,
        )

        val HTTP_GET = Action("http", "GET")
        val ORDER_PATH = Resource("http", "/orders/1", emptyMap())
        val ORDER_DELETE = Action("order", "delete")
        val ORDER = Resource("order", "1", emptyMap())

        /**
         * The request a host permitted for [id] of [tenant], with `role@space` [roles] and attached [policies], whose
         * credential declares [app] (the control app by default).
         */
        fun security(
            tenant: TenantId,
            id: String,
            roles: List<String> = emptyList(),
            policies: List<String> = emptyList(),
            app: AppId = PipelineControlAuthorizer.CONTROL_APP,
        ): RequestSecurity {
            val principal = AuthenticatedPrincipal(
                PrincipalId(id),
                tenant,
                MembershipClaims.parse(roles, policies),
                emptyMap(),
                Identity.Credential(Identity.Method("test"), "token-$id", null),
                app = app,
            )
            val host = Pipeline.builder(
                Authenticator { AuthnResult.Authenticated(principal, "test") },
                SuspendingSnapshotSource(Views(emptyMap())),
            ).engine(PERMITTING).build()
            val outcome = runBlocking { host.evaluate(CONTROL_API) }
            check(outcome.proceed) { "the host did not permit the control API: ${outcome.status}" }
            return checkNotNull(outcome.security)
        }

        private val CONTROL_API = HttpFacts(
            method = "POST",
            rawPath = "/cosec/control/v1/changes",
            rawQuery = null,
            headers = Headers.of(emptyMap()),
            cookies = Cookies.of(emptyMap()),
            remoteAddress = "10.0.0.1",
            host = HostScope(null),
        )

        /** The host's coarse rule, "the control API is open to authenticated principals", as a permit. */
        private val PERMITTING = object : FlatEngine {
            override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel): FlatEvaluation =
                FlatEvaluation(
                    Decision.Permit(
                        StatementRef.Policy(
                            PolicyId("control-api"),
                            0,
                            "open",
                            BindingRef.Attached(PolicyId("control-api"))
                        ),
                    ),
                    null,
                )
        }
    }
}
