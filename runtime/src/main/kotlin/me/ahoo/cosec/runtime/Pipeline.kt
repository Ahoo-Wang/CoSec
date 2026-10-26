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

package me.ahoo.cosec.runtime

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.membership.MembershipProvider
import me.ahoo.cosec.membership.MembershipUnavailableException
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.runtime.authn.AuthenticatedPrincipal
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.authn.Credentials
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.PathNormalizer
import me.ahoo.cosec.runtime.http.QueryParameters
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosid.StringIdGenerator
import java.time.Clock
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import me.ahoo.cosec.model.Scope as DeclaredRequestScope

private val log = KotlinLogging.logger {}

/**
 * The request pipeline (RFC 0006 §5.8): one suspend function from [HttpFacts] to [Outcome], which adapters write.
 *
 * Normalize the path and read the `CoSec-*` parameters (400 when ambiguous) → [Authenticator] → memberships → scope
 * → snapshot view → engine → [StatusMapping]. External attributes, the blacklist (RFC 0006 PR-4), rate-limit
 * obligations (PR-5) and decision listeners (PR-6) join later; the starter wires the pipeline at the switch.
 *
 * Fail closed, structurally: only a [Decision.Permit] makes an outcome that proceeds. Each SPI call that fails, throws
 * or does not answer in time is a 503; an exception nothing modelled is a 500; and an external cancellation makes no
 * outcome at all (§7.4, `PIP-3`). Every SPI call goes through [resumeOffIo], so a request that never really suspends
 * finishes on its calling thread without a dispatch (§7.2).
 */
class Pipeline(
    private val authenticator: Authenticator,
    private val snapshots: SuspendingSnapshotSource,
    /** Asked when a token carries the provider marker instead of claims; without one such a request is a 503. */
    private val membershipProvider: MembershipProvider? = null,
    private val engine: FlatEngine = CoSecFlatEngine,
    /** Request ids for requests without a `CoSec-Request-Id` header. */
    private val idGenerator: StringIdGenerator = RandomRequestIds,
    private val clock: Clock = Clock.systemUTC(),
    private val options: PipelineOptions = PipelineOptions(),
) {
    private val memberships = MembershipResolver(membershipProvider, options.membershipTimeout)

    /**
     * The only entry point of the adapters. An external cancellation propagates unchanged and makes no outcome;
     * everything else, a defect included, becomes one. An [Error] propagates too: the host answers it (§7.4).
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun evaluate(facts: HttpFacts): Outcome {
        val requestId = requestId(facts)
        return try {
            // A ...OrNull timeout, never withTimeout: its TimeoutCancellationException would look like an external
            // cancellation below and vanish instead of answering 503 (RFC 0006 K13). Lazy: a request that never
            // suspends arms no timer.
            withLazyTimeoutOrNull(options.timeout) { decide(facts, requestId) }
                ?: StatusMapping.unavailable(requestId, Unavailability.DeadlineExceeded, null)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw e
            }
            // Nobody cancelled this request: something threw a CancellationException of its own.
            log.error(e) { "Request [$requestId] failed with a stray cancellation." }
            StatusMapping.error(requestId, e, null)
        } catch (e: Exception) {
            log.error(e) { "Request [$requestId] failed with an unmodelled exception." }
            StatusMapping.error(requestId, e, null)
        }
    }

    private suspend fun decide(facts: HttpFacts, requestId: String): Outcome {
        val path = PathNormalizer.normalize(facts.rawPath)
            ?: return StatusMapping.invalidRequest(requestId, "path", null)
        val parameters = CoSecParameters.read(facts)
            ?: return StatusMapping.invalidRequest(requestId, "CoSec-* parameter", null)

        val authn = authenticate(credentials(facts))
        if (authn is AuthnResult.Unavailable) {
            return StatusMapping.unavailable(requestId, Unavailability.AuthenticatorUnavailable(authn.cause), null)
        }
        // A rejected credential is evaluated as anonymous (RFC 0006 K2).
        val principal = (authn as? AuthnResult.Authenticated)?.principal

        val memberships = when (val fetched = memberships(principal)) {
            is Fetched.Value -> fetched.value
            is Fetched.Failed -> return unavailable(requestId, Cause.MembershipUnavailable(fetched.error))
        }

        val subject = ScopeSubject(principal?.tenant ?: TenantId.DEFAULT, principal != null, memberships)
        val declared = DeclaredScope(parameters.appId, parameters.spaceId)
        val scope = when (val resolution = ScopeResolver.resolve(subject, declared, facts.host)) {
            is ScopeResolution.Resolved -> resolution.scope
            is ScopeResolution.Rejected -> {
                val denied = Decision.Deny(StatementRef.ScopeRejected(resolution.reason))
                return StatusMapping.decided(requestId, denied, authn, null)
            }
        }

        // The tenant is the scope's from here on: ScopeResolver makes it the principal's tenant (the default one when
        // anonymous), the view loads that tenant's document, and the engine refuses a view of another tenant.
        //
        // Not blocked: the blacklist stage (RFC 0006 PR-4) is not here yet. When it is, a failed lookup must become an
        // unresolved input (Indeterminate, 503), never "not blocked": the engine sees only this yes/no value.
        val view = when (val fetched = call(null) { snapshots.view(scope, blocked = false) }) {
            is Fetched.Value -> fetched.value
            is Fetched.Failed -> return unavailable(
                requestId,
                Cause.SourceUnavailable(DocumentKind.PLATFORM, fetched.error)
            )
        }

        val request = FlatRequest(
            authzRequest(principal, scope, path, parameters, facts, requestId),
            scope,
            memberships,
            clock.millis(),
        )
        val decision = engine.evaluate(request, view, options.traceLevel).decision
        return StatusMapping.decided(requestId, decision, authn, null)
    }

    /** The authenticator's answer; a failure, a stray cancellation or no answer in time is [AuthnResult.Unavailable]. */
    private suspend fun authenticate(credentials: Credentials): AuthnResult =
        when (val fetched = call(options.authenticationTimeout) { authenticator.authenticate(credentials) }) {
            is Fetched.Value -> fetched.value
            is Fetched.Failed -> AuthnResult.Unavailable(fetched.error)
        }

    /**
     * The memberships of [principal] (RFC 0004 §4.5): none for an anonymous one, else what [memberships] resolves. Its
     * failure is a failure, never "no memberships": no roles would also mean no `inRole` DENY matches.
     */
    private suspend fun memberships(principal: AuthenticatedPrincipal?): Fetched<Memberships> {
        if (principal == null) {
            return NO_MEMBERSHIPS
        }
        return try {
            Fetched.Value(memberships.resolve(principal.tenant, principal.id, principal.membershipSource))
        } catch (e: MembershipUnavailableException) {
            Fetched.Failed(e)
        }
    }

    /**
     * Calls an SPI through [resumeOffIo], within [timeout] when there is one. An external cancellation propagates;
     * anything else that goes wrong, a CancellationException the SPI throws while this request is active included, is
     * [Fetched.Failed] (RFC 0006 §7.4).
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T : Any> call(timeout: Duration?, spi: suspend () -> T): Fetched<T> = try {
        val value = if (timeout == null) resumeOffIo(spi) else withLazyTimeoutOrNull(timeout) { resumeOffIo(spi) }
        if (value == null) Fetched.Failed(TimeoutException("No answer within $timeout.")) else Fetched.Value(value)
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) {
            throw e
        }
        Fetched.Failed(e)
    } catch (e: Exception) {
        Fetched.Failed(e)
    }

    private fun unavailable(requestId: String, cause: Cause): Outcome =
        StatusMapping.unavailable(requestId, Unavailability.Indeterminate(cause), null)

    private fun requestId(facts: HttpFacts): String =
        facts.headers.values(CoSecParameters.REQUEST_ID).firstOrNull { it.isNotBlank() } ?: idGenerator.generateAsString()

    /** As `DefaultSecurityContextParser`: the header, else the query parameter when allowed, else the cookie. */
    private fun credentials(facts: HttpFacts): Credentials {
        val token = facts.headers.values(AUTHORIZATION).firstOrNull { it.isNotBlank() }
            ?: queryToken(facts)
            ?: facts.cookies.values(AUTHORIZATION).firstOrNull { it.isNotBlank() }
        val apiKey = facts.headers.values(options.apiKeyHeader).firstOrNull { it.isNotBlank() }
        return if (token == null && apiKey == null) NO_CREDENTIALS else Credentials(token, apiKey)
    }

    private fun queryToken(facts: HttpFacts): String? {
        if (!options.allowQueryToken) {
            return null
        }
        return QueryParameters.values(
            facts.rawQuery,
            AUTHORIZATION_NAMES
        )?.get(AUTHORIZATION)?.firstOrNull { it.isNotBlank() }
    }

    /**
     * The request the engine reads. Until the request model changes at the switch, it carries the RFC 0002 types; the
     * engine takes the tenant from [scope] and reads only the subject's id and whether it is authenticated.
     */
    private fun authzRequest(
        principal: AuthenticatedPrincipal?,
        scope: Scope,
        path: String,
        parameters: CoSecParameters,
        facts: HttpFacts,
        requestId: String,
    ): AuthzRequest = AuthzRequest(
        subject = Subject(
            id = principal?.id?.value ?: CoSecPrincipal.ANONYMOUS_ID,
            tenantId = scope.tenant.value,
            authenticated = principal != null,
            roles = emptySet(),
            policies = emptySet(),
            attributes = principal?.attributes.orEmpty(),
            root = false,
        ),
        scope = DeclaredRequestScope.declared(scope.tenant.value, scope.app.value, scope.space.value),
        action = Action(HTTP, facts.method),
        resource = Resource(HTTP, path, emptyMap()),
        environment = Environment(
            remoteIp = facts.remoteAddress,
            requestId = requestId,
            deviceId = parameters.deviceId,
            blocked = false,
        ),
    )

    /** What an SPI call gave: a value, or why not. Internal to the pipeline, so failures stay values (§7.4). */
    private sealed interface Fetched<out T : Any> {
        class Value<out T : Any>(val value: T) : Fetched<T>

        class Failed(val error: Throwable) : Fetched<Nothing>
    }

    private companion object {
        const val HTTP = "http"
        const val AUTHORIZATION = "authorization"
        val AUTHORIZATION_NAMES = setOf(AUTHORIZATION)
        val NO_CREDENTIALS = Credentials(null, null)
        val NO_MEMBERSHIPS: Fetched<Memberships> =
            Fetched.Value(Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList()))
    }
}
