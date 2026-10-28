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

package me.ahoo.cosec.runtime.identity

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.identity.Identity
import me.ahoo.cosec.identity.IdentityCodec
import me.ahoo.cosec.identity.IdentityFormatException
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.ProblemType
import me.ahoo.cosec.runtime.RandomRequestIds
import me.ahoo.cosec.runtime.RequestGate
import me.ahoo.cosec.runtime.RequestIds
import me.ahoo.cosec.runtime.RequestSecurity
import me.ahoo.cosec.runtime.StatusMapping
import me.ahoo.cosec.runtime.authn.AuthenticatedPrincipal
import me.ahoo.cosec.runtime.event.TraceContext
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosid.StringIdGenerator
import java.time.Clock
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import me.ahoo.cosec.model.Scope as DeclaredRequestScope

private val log = KotlinLogging.logger {}

/**
 * The gate of a service behind a gateway (`cosec.pipeline.mode=gateway`): it trusts the network boundary and the
 * gateway in front, and reads who the caller is from the `CoSec-Identity` header the gateway forwarded ([IdentityCodec]),
 * with no keys, policies, snapshot or Redis of its own. The gateway strips any such header a client sends.
 *
 * - no header: the request proceeds as anonymous, and the service's own code decides;
 * - a header that cannot be read, is larger than [maxBytes] or arrives more than once: 400 `invalid-request`;
 * - a version this service does not read, or an identity from the future: 401 `token-invalid`;
 * - a credential past its expiry, or an identity older than [maxAge] (with [skew]), which limits replaying a captured
 *   header inside the network: 401 `token-expired`;
 * - otherwise the request proceeds as that principal, in that scope, with those roles; never as anonymous instead.
 */
class IdentityGate(
    /** `cosec.pipeline.gateway.max-size`. */
    private val maxBytes: Int = IdentityCodec.DEFAULT_MAX_BYTES,
    /** `cosec.pipeline.gateway.max-age`. */
    private val maxAge: Duration = DEFAULT_MAX_AGE,
    /** `cosec.authentication.revocation.skew`: how far the gateway's clock may be ahead of this service's. */
    private val skew: Duration = DEFAULT_SKEW,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: StringIdGenerator? = null,
) : RequestGate {
    init {
        require(maxBytes > 0) { "The identity size limit must be positive, not $maxBytes." }
        require(maxAge > Duration.ZERO) { "The identity maximum age must be positive, not $maxAge." }
        require(!skew.isNegative) { "The skew must not be negative, not $skew." }
    }

    /** Every answer is an outcome of `StatusMapping`: what nothing here models is a 500, never an exception. */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun evaluate(facts: HttpFacts): Outcome {
        val requestId = facts.headers.values(CoSecParameters.REQUEST_ID).firstOrNull(RequestIds::isAcceptable)
            ?: idGenerator?.generateAsString() ?: RandomRequestIds.next()
        val traceId = currentCoroutineContext()[TraceContext]?.traceId
        return try {
            decide(facts, requestId, traceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Request [$requestId]: the forwarded identity could not be read." }
            StatusMapping.error(requestId, e, traceId)
        }
    }

    private fun decide(facts: HttpFacts, requestId: String, traceId: String?): Outcome {
        val headers = facts.headers.values(IdentityCodec.HEADER)
        if (headers.isEmpty()) {
            return StatusMapping.asserted(requestId, anonymous(requestId, facts), null)
        }
        if (headers.size > 1) {
            return reject(requestId, ProblemType.INVALID_REQUEST, "the identity header arrived more than once", traceId)
        }
        val identity = try {
            IdentityCodec.decode(headers.single(), maxBytes)
        } catch (e: IdentityFormatException) {
            val type = when (e.reason) {
                IdentityFormatException.Reason.UNSUPPORTED_VERSION, IdentityFormatException.Reason.UNBOUNDED ->
                    ProblemType.TOKEN_INVALID
                IdentityFormatException.Reason.MALFORMED, IdentityFormatException.Reason.OVERSIZE ->
                    ProblemType.INVALID_REQUEST
            }
            return reject(requestId, type, e.message.orEmpty(), traceId)
        }
        val now = clock.instant()
        identity.credential.expiresAt?.takeIf { it.isBefore(now.minus(skew)) }?.let {
            return reject(requestId, ProblemType.TOKEN_EXPIRED, "the credential expired at $it", traceId)
        }
        if (identity.issuedAt.plus(maxAge).plus(skew).isBefore(now)) {
            return reject(
                requestId,
                ProblemType.TOKEN_EXPIRED,
                "the identity was issued at ${identity.issuedAt}",
                traceId
            )
        }
        if (identity.issuedAt.isAfter(now.plus(skew))) {
            return reject(requestId, ProblemType.TOKEN_INVALID, "the identity is from ${identity.issuedAt}", traceId)
        }
        val security = security(identity, requestId, facts)
            ?: return reject(requestId, ProblemType.INVALID_REQUEST, "the identity's scope does not resolve here", traceId)
        return StatusMapping.asserted(requestId, security, identity)
    }

    private fun reject(requestId: String, type: ProblemType, reason: String, traceId: String?): Outcome {
        log.debug { "Request [$requestId]: the forwarded identity is refused, $reason." }
        return StatusMapping.identityRejected(requestId, type, reason, traceId)
    }

    private fun anonymous(requestId: String, facts: HttpFacts): RequestSecurity {
        val memberships = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())
        val subject = ScopeSubject(TenantId.DEFAULT, false, memberships)
        val resolution = ScopeResolver.resolve(subject, DeclaredScope("", ""), facts.host)
        val scope = (resolution as ScopeResolution.Resolved).scope
        return RequestSecurity(requestId, null, scope, memberships, request(ANONYMOUS, scope, requestId, facts))
    }

    /** The principal the gateway verified, in the scope it decided in; null when that scope does not resolve here. */
    private fun security(identity: Identity, requestId: String, facts: HttpFacts): RequestSecurity? {
        val tenant = TenantId(identity.tenant)
        val roles = identity.roles.mapTo(LinkedHashSet(), ::RoleId)
        val memberships = Memberships(Memberships.Source.CLAIMS, mapOf(SpaceId(identity.space) to roles), emptyList())
        val principal = AuthenticatedPrincipal(
            PrincipalId(identity.subject),
            tenant,
            memberships,
            identity.claims,
            identity.credential,
        )
        val resolution = ScopeResolver.resolve(
            ScopeSubject(tenant, true, memberships),
            DeclaredScope(identity.app, identity.space),
            facts.host,
        )
        val scope = (resolution as? ScopeResolution.Resolved)?.scope ?: return null
        val subject = Subject(identity.subject, identity.tenant, true, emptySet(), emptySet(), identity.claims, false)
        val request = request(subject, scope, requestId, facts)
        return RequestSecurity(requestId, principal, scope, memberships, request, identity = identity)
    }

    private fun request(subject: Subject, scope: Scope, requestId: String, facts: HttpFacts) =
        AuthzRequest(
            subject = subject,
            scope = DeclaredRequestScope.declared(scope.tenant.value, scope.app.value, scope.space.value),
            action = Action("http", facts.method),
            resource = Resource("http", facts.rawPath, emptyMap()),
            environment = Environment(facts.remoteAddress, requestId, "", false),
        )

    companion object {
        val DEFAULT_MAX_AGE: Duration = Duration.ofSeconds(60)
        val DEFAULT_SKEW: Duration = Duration.ofSeconds(5)

        private val ANONYMOUS = Subject(
            CoSecPrincipal.ANONYMOUS_ID,
            TenantId.DEFAULT.value,
            false,
            emptySet(),
            emptySet(),
            emptyMap(),
            false
        )
    }
}
