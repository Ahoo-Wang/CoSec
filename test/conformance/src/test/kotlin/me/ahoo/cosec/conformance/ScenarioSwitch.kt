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

package me.ahoo.cosec.conformance

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
import me.ahoo.cosec.login.token.SwitchTenantAuthentication
import me.ahoo.cosec.login.token.SwitchTenantCredentials
import me.ahoo.cosec.login.token.TenantMembership
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
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
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.testkit.only
import me.ahoo.cosec.testkit.required
import me.ahoo.cosec.testkit.strings
import me.ahoo.cosec.testkit.text
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.cancellation.CancellationException

/**
 * A scenario's `"switch"` (PipelineRunner): the access token it signs authenticates a request through CoSec's token
 * authenticator, over the scenario's `"revocations"` (the request path's reading, PIP-7), and that request is switched
 * to `targetTenantId` through [SwitchTenantAuthentication], whose `TenantSwitch` answers `answer` (`roles`, `app`),
 * with the credentials naming `targetAppId` when given. The switch issues from the request's session, over the
 * scenario's `"families"` (as `"refresh"` reads them, at [ScenarioRefresh.NOW]); without them, the token's family is
 * there and live.
 *
 * ```
 * "switch": { "access": { "jti": "a1", "sub": "alice", "tenantId": "acme", "claims": { "sid": "f1", "auth_time": 1 } },
 *             "targetTenantId": "globex", "targetAppId": "order", "answer": { "roles": ["clerk"], "app": "order" } }
 * ```
 *
 * The outcome is the issued token's tenant and app (200); 401 when the switch refuses (`token-revoked`,
 * `token-expired`, `token-invalid`, or `switch-refused` for its `TenantSwitch`'s answer); 503 when a store cannot be
 * read, so nothing is issued (`IDN-14`, `IDN-19`).
 */
internal object ScenarioSwitch {
    fun switched(node: JsonNode, revocations: RevocationReader, families: JsonNode?): Outcome {
        node.only("access", "targetTenantId", "targetAppId", "answer")
        val access = ScenarioJwt(node.required("access")).token
        val answer = node.get("answer") ?: JsonNodeFactory.instance.objectNode()
        answer.only("roles", "app")
        val membership = TenantMembership(
            roles = answer.get("roles")?.strings()?.toSet().orEmpty(),
            app = answer.get("app")?.text(),
        )
        val store = if (families == null) familyOf(access) else ScenarioRefresh.familyStore(families)
        val tokenFamilies = TokenFamilies(
            store,
            ScenarioRefresh.LIFETIME,
            ScenarioRefresh.LEEWAY,
            clock = Clock.fixed(ScenarioRefresh.NOW, ZoneOffset.UTC),
        )
        val context = requestWith(access, revocations)
        if (!context.principal.authenticated) {
            return Outcome.Answered(401, false, "token-invalid")
        }
        val credentials = Credentials(
            targetTenantId = node.required("targetTenantId").text(),
            targetAppId = node.get("targetAppId")?.text(),
            context = context,
        )
        val switch = SwitchTenantAuthentication(tokenFamilies, TokenRevocation(revocations), Credentials::class.java) {
            membership
        }
        return try {
            val subject = runBlocking { switch.authenticate(credentials) }
            Outcome.Answered(200, true, null, issued = mapOf("tenant" to subject.tenant, "app" to subject.app))
        } catch (_: TokenRevokedException) {
            Outcome.Answered(401, false, "token-revoked")
        } catch (_: TokenExpiredException) {
            Outcome.Answered(401, false, "token-expired")
        } catch (_: TokenVerificationException) {
            Outcome.Answered(401, false, "token-invalid")
        } catch (_: RevocationUnavailableException) {
            Outcome.Answered(503, false, "unavailable")
        } catch (_: IllegalStateException) {
            // A family record that cannot be read (the scenario's "unavailable"): nothing is issued.
            Outcome.Answered(503, false, "unavailable")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Outcome.Answered(401, false, "switch-refused")
        }
    }

    private class Credentials(
        override val targetTenantId: String,
        override val targetAppId: String?,
        override val context: SecurityContext,
    ) : SwitchTenantCredentials

    /** The token's own family, live: for a scenario about something else than the session. */
    private fun familyOf(access: String): TokenFamilyStore {
        val token = ScenarioJwt.VERIFIER.verify(access)
        val store = InMemoryTokenFamilyStore { ScenarioRefresh.NOW }
        token.session?.let { session ->
            val family = TokenFamily(
                id = session.id,
                tenant = TenantId(token.tenant ?: TenantId.DEFAULT.value),
                principal = PrincipalId(token.principal),
                authTime = session.authTime,
                expiresAt = session.authTime.plus(ScenarioRefresh.LIFETIME),
                generation = session.generation,
                rotatedAt = null,
                revoked = false,
            )
            runBlocking { store.create(family) }
        }
        return store
    }

    /** The request made with [access], as the pipeline lets it through: a token it cannot verify is anonymous. */
    private fun requestWith(access: String, revocations: RevocationReader): SecurityContext {
        val pipeline = Pipeline.builder(
            TokenAuthenticator(ScenarioJwt.VERIFIER, revocations),
            SuspendingSnapshotSource(EMPTY)
        )
            .engine(PERMITTING)
            .build()
        val facts = HttpFacts(
            method = "POST",
            rawPath = "/switch",
            rawQuery = null,
            headers = Headers.of(mapOf("Authorization" to listOf("Bearer $access"))),
            cookies = Cookies.of(emptyMap()),
            remoteAddress = "10.0.0.1",
            host = HostScope(null),
        )
        val outcome = runBlocking { pipeline.evaluate(facts) }
        return checkNotNull(outcome.security) { "the pipeline answered ${outcome.status}" }.securityContext
    }

    /** The host's rule for the switch endpoint, "open to every request": the switch itself decides. */
    private val PERMITTING = object : Engine {
        override fun evaluate(request: EngineRequest, snapshot: SnapshotView, trace: TraceLevel): Evaluation =
            Evaluation(
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
