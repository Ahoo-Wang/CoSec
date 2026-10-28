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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.identity.IdentityOverflowException
import me.ahoo.cosec.limiter.RateLimitResult
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
import me.ahoo.cosec.model.decision.Obligation
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.route.RouteResolution
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.attribute.AttributeRequest
import me.ahoo.cosec.runtime.attribute.BlacklistAttributeProvider
import me.ahoo.cosec.runtime.attribute.FetchedAttributes
import me.ahoo.cosec.runtime.authn.AuthenticatedPrincipal
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.authn.Credentials
import me.ahoo.cosec.runtime.authn.MembershipSource
import me.ahoo.cosec.runtime.event.ReplayHandle
import me.ahoo.cosec.runtime.event.TraceContext
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.http.HttpAttributes
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.PathNormalizer
import me.ahoo.cosec.runtime.http.QueryParameters
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.runtime.ratelimit.RateLimitFailures
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import me.ahoo.cosec.model.Scope as DeclaredRequestScope

private val log = KotlinLogging.logger {}

/**
 * The request pipeline (RFC 0006 §5.8): one suspend function from [HttpFacts] to [Outcome], which adapters write.
 *
 * Normalize the path and read the `CoSec-*` parameters (400 when ambiguous) → [Authenticator] → memberships, together
 * with the stage-A attributes such as the blacklist → scope → snapshot view → attribute plan and the stage-B
 * attributes → engine → [StatusMapping] (§5.4) → REQUIRED decision listeners, then BEST_EFFORT ones (§9). Rate-limit
 * obligations (PR-5) join later; the starter wires the pipeline at the switch.
 *
 * Fail closed, structurally: only a [Decision.Permit] makes an outcome that proceeds. Each SPI call that fails, throws
 * or does not answer in time is a 503; an exception nothing modelled is a 500; and an external cancellation makes no
 * outcome at all (§7.4, `PIP-3`). Every SPI call goes through [resumeOffIo], so a request that never really suspends
 * finishes on its calling thread without a dispatch (§7.2).
 */
class Pipeline(components: PipelineComponents) : RequestGate {
    private val authenticator = components.authenticator
    private val snapshots = components.snapshots
    private val engine = components.engine
    private val idGenerator = components.idGenerator
    private val clock = components.clock
    private val options = components.options
    private val attributes = components.attributes
    private val listeners = components.listeners
    private val rateLimiter = components.rateLimiter
    private val routes = components.routes
    private val identity = components.identity
    private val memberships = MembershipResolver(components.membershipProvider, options.membershipTimeout)
    private val rateLimitFailures = RateLimitFailures()

    /**
     * The only entry point of the adapters. An external cancellation propagates unchanged and makes no outcome;
     * everything else, a defect included, becomes one. An [Error] propagates too: the host answers it (§7.4).
     */
    override suspend fun evaluate(facts: HttpFacts): Outcome {
        val requestId = requestId(facts)
        // The trace the bridge captured (§7.5): its id goes into problem bodies and decision events.
        val trace = currentCoroutineContext()[TraceContext]
        val traceId = trace?.traceId
        if (listeners.isEmpty) {
            return evaluate(facts, requestId, traceId, null)
        }
        val record = RequestRecord(System.nanoTime(), clock.millis(), trace)
        val outcome = try {
            evaluate(facts, requestId, traceId, record)
        } catch (e: CancellationException) {
            // Only an external cancellation gets here: a CANCELLED event, and still no outcome (§7.4).
            if (listeners.hasBestEffort) {
                listeners.publish(record.event(eventId(), requestId, facts, null))
            }
            throw e
        }
        // REQUIRED listeners had their event in [required]; without a BEST_EFFORT one, nobody would read this one.
        if (listeners.hasBestEffort) {
            listeners.publish(record.event(eventId(), requestId, facts, outcome, replay(record)))
        }
        return outcome
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun evaluate(
        facts: HttpFacts,
        requestId: String,
        traceId: String?,
        record: RequestRecord?,
    ): Outcome = try {
        // A ...OrNull timeout, never withTimeout: its TimeoutCancellationException would look like an external
        // cancellation below and vanish instead of answering 503 (RFC 0006 K13). Lazy: a request that never
        // suspends arms no timer.
        val decided = withLazyTimeoutOrNull(options.timeout) { decide(facts, requestId, traceId, record) }
            ?: StatusMapping.unavailable(requestId, Unavailability.DeadlineExceeded, traceId)
        val forwarded = identity?.let { forward(it, decided, facts, traceId) } ?: decided
        if (record == null) forwarded else required(facts, requestId, traceId, forwarded, record)
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) {
            throw e
        }
        // Nobody cancelled this request: something threw a CancellationException of its own.
        log.error(e) { "Request [$requestId] failed with a stray cancellation." }
        StatusMapping.error(requestId, e, traceId)
    } catch (e: Exception) {
        log.error(e) { "Request [$requestId] failed with an unmodelled exception." }
        StatusMapping.error(requestId, e, traceId)
    }

    /**
     * The REQUIRED listeners (§9.4.2): they see the outcome so far, and a failure turns only a permit into 503; any
     * other outcome keeps its status, and the failure is logged and counted.
     *
     * A permit's delivery shares the pipeline deadline: a store that does not answer in what remains of it is a 503,
     * never a request held past `cosec.pipeline.timeout`. Any other outcome is not held to it: its status cannot change,
     * so a slow listener only costs time, bounded by each listener's own timeout (review R2: a slow audit made a 401 a
     * 503 before).
     */
    private suspend fun required(
        facts: HttpFacts,
        requestId: String,
        traceId: String?,
        outcome: Outcome,
        record: RequestRecord
    ): Outcome {
        if (listeners.required.isEmpty()) {
            return outcome
        }
        val event = record.event(eventId(), requestId, facts, outcome)
        val failed = if (outcome.proceed) {
            val remaining = options.timeout - (System.nanoTime() - record.startNanos).nanoseconds
            val delivered = remaining.takeIf { it.isPositive() }?.let { left ->
                withLazyTimeoutOrNull(left) { listeners.required(event) }
            }
            delivered ?: return StatusMapping.unavailable(requestId, Unavailability.DeadlineExceeded, traceId)
        } else {
            listeners.required(event)
        }
        if (failed.isEmpty()) {
            return outcome
        }
        record.requiredFailures = failed
        if (!outcome.proceed) {
            listeners.requiredFailedOnNonPermit(event, failed)
            return outcome
        }
        return unavailable(requestId, traceId, Cause.RequiredListenerFailed(failed))
    }

    /**
     * The decision for [action] on [resource] as [security] (RFC 0006 §7.6): the second half of [decide] (snapshot
     * view, attribute plan, engine) for the principal and scope of a permitted request. A query, not a request: no
     * obligation runs, no listener hears of it, and nothing fails open: an unreadable view, an error or a permit only
     * break-glass gives is not a permit.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun check(security: RequestSecurity, action: Action, resource: Resource): Decision {
        val view = when (val loaded = call(null) { snapshots.view(security.scope, false) }) {
            is Fetched.Value -> loaded.value
            is Fetched.Failed -> return Decision.Indeterminate(
                Cause.SourceUnavailable(DocumentKind.PLATFORM, loaded.error)
            )
        }
        val authz = with(security.request) { AuthzRequest(subject, scope, action, resource, environment) }
        val request = FlatRequest(authz, security.scope, security.memberships, clock.millis(), security.attributes)
        val decision = evaluate(request, view, fetchedAttributes(), null)
        // A check writes no audit, and every use of break-glass is written to a REQUIRED one (§9.4.3): a check that
        // only break-glass permits is refused (review A1).
        if (decision is Decision.Permit && view.platform.isBreakGlass(decision.by.via)) {
            return Decision.Indeterminate(Cause.RequiredListenerFailed(emptyList()))
        }
        return decision
    }

    /** What a shadow listener needs, only when one samples this request (§9.5.4). */
    private fun replay(record: RequestRecord): ReplayHandle? {
        if (listeners.shadows.isEmpty()) {
            return null
        }
        val request = record.request ?: return null
        val view = record.view ?: return null
        var sampled: MutableSet<String>? = null
        for (shadow in listeners.shadows) {
            if (shadow.sample()) {
                (sampled ?: HashSet<String>().also { sampled = it }) += shadow.name
            }
        }
        return sampled?.let { ReplayHandle(request, view, it) }
    }

    private fun eventId(): String = idGenerator?.generateAsString() ?: RandomRequestIds.next()

    private suspend fun decide(facts: HttpFacts, requestId: String, traceId: String?, record: RequestRecord?): Outcome {
        val path = PathNormalizer.normalize(facts.rawPath)
            ?: return StatusMapping.invalidRequest(requestId, "path", traceId)
        val parameters = CoSecParameters.read(facts)
            ?: return StatusMapping.invalidRequest(requestId, "CoSec-* parameter", traceId)
        val (action, resource) = when (val route = routes.resolve(facts.method, path)) {
            RouteResolution.Unmapped -> Action(HTTP, facts.method) to Resource(HTTP, path, emptyMap())
            is RouteResolution.Mapped -> route.action to route.resource
            // A capture that would change the resource id's segments: refused, never read as another resource.
            is RouteResolution.Invalid -> return StatusMapping.invalidRequest(requestId, "path", traceId)
        }
        val fetched = fetchedAttributes()

        val authn = authenticate(credentials(facts))
        record?.authenticated(path, parameters.deviceId, authn, action, resource, fetched)
        if (authn is AuthnResult.Unavailable) {
            return StatusMapping.unavailable(requestId, Unavailability.AuthenticatorUnavailable(authn.cause), traceId)
        }
        // A rejected credential is evaluated as anonymous (RFC 0006 K2).
        val principal = (authn as? AuthnResult.Authenticated)?.principal

        val tenant = principal?.tenant ?: TenantId.DEFAULT
        val authzSubject = subject(principal, tenant)

        val stageA = if (fetched == null || attributes.stageA.isEmpty()) {
            null
        } else {
            AttributeRequest(authzSubject, null, action, resource, facts.remoteAddress, requestId, parameters.deviceId)
        }
        val membershipsFetched = membershipsWithStageA(principal, stageA, fetched)
        val memberships = membershipsFetched.valueOr {
            return unavailable(requestId, traceId, Cause.MembershipUnavailable(it))
        }

        val subject = ScopeSubject(tenant, principal != null, memberships)
        val declared = DeclaredScope(parameters.appId, parameters.spaceId)
        val scope = when (val resolution = ScopeResolver.resolve(subject, declared, facts.host)) {
            is ScopeResolution.Resolved -> resolution.scope
            is ScopeResolution.Rejected -> {
                val denied = Decision.Deny(StatementRef.ScopeRejected(resolution.reason))
                return StatusMapping.decided(requestId, denied, authn, traceId)
            }
        }

        // The tenant is the scope's from here on: ScopeResolver makes it the principal's tenant (the default one when
        // anonymous), the view loads that tenant's document, and the engine refuses a view of another tenant.
        val blacklist = blacklist(fetched)
        val view = view(scope, blacklist).valueOr {
            return unavailable(requestId, traceId, Cause.SourceUnavailable(DocumentKind.PLATFORM, it))
        }
        record?.resolved(scope, view)

        val authz = AuthzRequest(
            subject = authzSubject,
            scope = DeclaredRequestScope.declared(scope.tenant.value, scope.app.value, scope.space.value),
            action = action,
            resource = resource,
            environment = Environment(
                remoteIp = facts.remoteAddress,
                requestId = requestId,
                deviceId = parameters.deviceId,
                blocked = blacklist.blocked,
            ),
        )
        val request = FlatRequest(authz, scope, memberships, clock.millis(), HttpAttributes(facts))
        val decision = evaluate(request, view, fetched, record)
        breakGlass(requestId, traceId, decision, view, record)?.let { return it }
        return decided(requestId, traceId, decision, authn, security(requestId, principal, request, decision))
    }

    /** The outcome of [decision]: a permit's obligations run first (§8.3). */
    private suspend fun decided(
        requestId: String,
        traceId: String?,
        decision: Decision,
        authn: AuthnResult,
        security: RequestSecurity?,
    ): Outcome {
        if (decision is Decision.Permit && decision.obligations.isNotEmpty()) {
            return obligations(requestId, traceId, decision, authn, security!!)
        }
        return StatusMapping.decided(requestId, decision, authn, traceId, security)
    }

    /**
     * Runs a permit's obligations in order (RFC 0006 §8.3), as 7.x `RateLimitingAuthorization` does: the first exhausted
     * rule is a 429 and the later rules spend nothing; earlier rules keep what they spent; the response reports the rule
     * with the fewest permits left. A rule that cannot be counted (its limiter failed, timed out or is missing, or its
     * bucket could not be read) refuses the request (503) when it is strict, and is skipped otherwise.
     */
    private suspend fun obligations(
        requestId: String,
        traceId: String?,
        permit: Decision.Permit,
        authn: AuthnResult,
        security: RequestSecurity,
    ): Outcome {
        var tightest: RateLimitResult? = null
        for (obligation in permit.obligations) {
            if (obligation !is Obligation.RateLimit) {
                continue
            }
            val result = acquire(obligation)
            when {
                result == null && obligation.strictFailure ->
                    return unavailable(requestId, traceId, Cause.RateLimiterUnavailable(obligation.ruleId))
                result == null -> continue
                !result.allowed -> return StatusMapping.tooManyRequests(
                    requestId,
                    permit,
                    obligation.ruleId,
                    result,
                    traceId
                )
                tightest == null || result.remaining < tightest.remaining -> tightest = result
            }
        }
        return StatusMapping.decided(requestId, permit, authn, traceId, security, tightest)
    }

    /** The limiter's answer for [obligation]; null when it cannot be counted, logged at most once per interval. */
    private suspend fun acquire(obligation: Obligation.RateLimit): RateLimitResult? {
        val bucket = obligation.bucket
        val limiter = rateLimiter
        val failure: Throwable = when {
            bucket == null -> IllegalStateException("The bucket of rule [${obligation.ruleId}] could not be read.")
            limiter == null -> IllegalStateException("No rate limiter is configured.")
            else -> when (
                val fetched = call(options.rateLimitTimeout) {
                    limiter.tryAcquire(obligation.ruleId, bucket, obligation.limit)
                }
            ) {
                is Fetched.Value -> return fetched.value
                is Fetched.Failed -> fetched.error
            }
        }
        rateLimitFailures.report(obligation, failure)
        return null
    }

    /** Who may proceed, for a permit only (§7.6). */
    private fun security(
        requestId: String,
        principal: AuthenticatedPrincipal?,
        request: FlatRequest,
        decision: Decision,
    ): RequestSecurity? = if (decision is Decision.Permit) {
        RequestSecurity(requestId, principal, request.scope, request.memberships, request.request, request.attributes)
    } else {
        null
    }

    /**
     * At a gateway that forwards the verified identity: what [outcome] tells the service behind, always naming the
     * header, so whatever the client sent is never forwarded. The identity on a permit with a principal unless the
     * route opts out ([IdentityIssuer.FORWARD_ATTRIBUTE]); otherwise none. An identity too large to forward fails the
     * request closed (500).
     */
    private fun forward(issuer: IdentityIssuer, outcome: Outcome, facts: HttpFacts, traceId: String?): Outcome {
        val security = outcome.security
        if (!outcome.proceed || security == null || facts.requestAttributes[IdentityIssuer.FORWARD_ATTRIBUTE] == "false") {
            return StatusMapping.forwarding(outcome, null)
        }
        return try {
            StatusMapping.forwarding(outcome, issuer.issue(security))
        } catch (e: IdentityOverflowException) {
            log.error(e) { "Request [${outcome.requestId}]: the identity cannot be forwarded." }
            StatusMapping.error(outcome.requestId, e, traceId)
        }
    }

    /** Where this request's external attributes go; null without providers. */
    private fun fetchedAttributes(): FetchedAttributes? = if (attributes.isEmpty) null else FetchedAttributes()

    /**
     * Every use of break-glass is written to a REQUIRED audit (RFC 0006 §9.4.3, Q20): without a REQUIRED listener, a
     * break-glass permit is a 503. With one, [required] lets it proceed only when they all succeed. Null otherwise.
     */
    private fun breakGlass(
        requestId: String,
        traceId: String?,
        decision: Decision,
        view: SnapshotView,
        record: RequestRecord?
    ): Outcome? {
        if (decision !is Decision.Permit || !view.platform.isBreakGlass(decision.by.via)) {
            return null
        }
        if (record == null || listeners.required.isEmpty()) {
            log.error { "Request [$requestId] permitted through break-glass, but no REQUIRED listener is registered." }
            return unavailable(requestId, traceId, Cause.RequiredListenerFailed(emptyList()))
        }
        record.breakGlass = true
        return null
    }

    /** Stage B, when there are providers, then the engine. */
    private suspend fun evaluate(
        request: FlatRequest,
        view: SnapshotView,
        fetched: FetchedAttributes?,
        record: RequestRecord?,
    ): Decision {
        fetched?.let { fetchStageB(request, view, it) }
        val evaluated = if (fetched == null || fetched.isEmpty) {
            // Nothing fetched reads as ExternalValues.NONE: every external attribute is missing.
            request
        } else {
            with(
                request
            ) { FlatRequest(this.request, scope, memberships, time, attributes, fetched.toExternalValues()) }
        }
        val decision = engine.evaluate(evaluated, view, options.traceLevel).decision
        record?.let {
            it.request = evaluated
            it.decision = decision
        }
        return decision
    }

    /**
     * The request's subject. Until the request model changes at the switch, the engine reads the RFC 0002 types: it
     * takes the tenant from the scope and reads only the subject's id and whether it is authenticated.
     */
    private fun subject(principal: AuthenticatedPrincipal?, tenant: TenantId): Subject = Subject(
        id = principal?.id?.value ?: CoSecPrincipal.ANONYMOUS_ID,
        tenantId = tenant.value,
        authenticated = principal != null,
        roles = emptySet(),
        policies = emptySet(),
        attributes = principal?.attributes.orEmpty(),
        root = false,
    )

    /** Stage A, when there is a [request] for it, together with the memberships (K17): neither waits for the other. */
    private suspend fun membershipsWithStageA(
        principal: AuthenticatedPrincipal?,
        request: AttributeRequest?,
        fetched: FetchedAttributes?,
    ): Fetched<Memberships> {
        if (request == null || fetched == null) {
            return memberships(principal)
        }
        if (principal?.membershipSource !is MembershipSource.Provider) {
            // Claims, or nothing to ask: the memberships never suspend, so there is nothing to overlap stage A with.
            val memberships = memberships(principal)
            fetchStageA(request, fetched)
            return memberships
        }
        return coroutineScope {
            val stageA = async(start = CoroutineStart.UNDISPATCHED) { fetchStageA(request, fetched) }
            val memberships = memberships(principal)
            stageA.await()
            memberships
        }
    }

    /**
     * The snapshot view for [scope]: the platform document only when [blacklist] blocks the request. A blacklist that
     * could not be read is never "not blocked": the evaluation is then indeterminate unless a DENY matches, as for a
     * document that could not be read (RFC 0006 §5.4.4).
     */
    private suspend fun view(scope: Scope, blacklist: Blacklist): Fetched<SnapshotView> {
        val loaded = call(null) { snapshots.view(scope, blacklist.blocked) }
        val unavailable = blacklist.unavailable ?: return loaded
        return when (loaded) {
            is Fetched.Value -> Fetched.Value(loaded.value.withUnresolved(unavailable))
            is Fetched.Failed -> loaded
        }
    }

    /** Every stage-A attribute, each provider in parallel. Records failures; never throws. */
    private suspend fun fetchStageA(request: AttributeRequest, fetched: FetchedAttributes) {
        val stageA = attributes.stageA
        if (stageA.size == 1) {
            fetchFrom(stageA[0], stageA[0].names, request, fetched)
            return
        }
        val answers = coroutineScope {
            stageA.map { async(start = CoroutineStart.UNDISPATCHED) { provide(it, it.names, request) } }
                .map { it.await() }
        }
        stageA.forEachIndexed { index, registered -> fetched.record(registered, registered.names, answers[index]) }
    }

    /**
     * The planned attributes stage A did not fetch, each provider in parallel with its own timeout: a provider that
     * fails or times out makes only its own attributes unavailable (§5.4.2). A planned attribute no provider declares
     * stays unavailable.
     */
    private suspend fun fetchStageB(request: FlatRequest, view: SnapshotView, fetched: FetchedAttributes) {
        if (!attributes.hasStageB) {
            // Only stage-A providers, the blacklist say, and stage A asked them for everything: no plan to make.
            return
        }
        if (request.request.environment.blocked) {
            // The blacklist rule decides; nothing else would be read.
            return
        }
        val plan = engine.plan(request, view)
        if (plan.isEmpty) {
            return
        }
        var groups: LinkedHashMap<AttributeProviders.Registered, MutableSet<String>>? = null
        for (attribute in plan.attributes) {
            val path = attribute.path
            if (fetched.asked(path)) {
                continue
            }
            val registered = attributes.registered(path) ?: continue
            val names = (groups ?: LinkedHashMap<AttributeProviders.Registered, MutableSet<String>>().also { groups = it })
                .getOrPut(registered) { LinkedHashSet() }
            names += path
        }
        val asked = groups ?: return
        val attributeRequest = AttributeRequest(
            request.request.subject,
            request.scope,
            request.request.action,
            request.request.resource,
            request.request.environment.remoteIp,
            request.request.environment.requestId,
            request.request.environment.deviceId,
        )
        if (asked.size == 1) {
            val (registered, names) = asked.entries.first()
            fetchFrom(registered, names, attributeRequest, fetched)
            return
        }
        val entries = asked.entries.toList()
        val answers = coroutineScope {
            entries.map { (registered, names) ->
                async(start = CoroutineStart.UNDISPATCHED) { provide(registered, names, attributeRequest) }
            }.map { it.await() }
        }
        entries.forEachIndexed { index, (registered, names) -> fetched.record(registered, names, answers[index]) }
    }

    private suspend fun fetchFrom(
        registered: AttributeProviders.Registered,
        names: Set<String>,
        request: AttributeRequest,
        fetched: FetchedAttributes,
    ) {
        fetched.record(registered, names, provide(registered, names, request))
    }

    /** What [registered] answered for [names]; null when it failed or did not answer in time. */
    private suspend fun provide(
        registered: AttributeProviders.Registered,
        names: Set<String>,
        request: AttributeRequest,
    ): Map<String, Any>? {
        val timeout = registered.provider.timeout ?: options.attributeTimeout
        return when (val answer = call(timeout) { registered.provider.fetch(request, names) }) {
            is Fetched.Value -> answer.value
            is Fetched.Failed -> {
                log.warn(answer.error) { "${registered.provider} failed to fetch $names." }
                null
            }
        }
    }

    /** Blocked when any blacklist attribute says so; else unavailable when any of them could not be read. */
    private fun blacklist(fetched: FetchedAttributes?): Blacklist {
        if (fetched == null) {
            return Blacklist.NOT_BLOCKED
        }
        var unavailable: MutableSet<String>? = null
        for (path in BlacklistAttributeProvider.ATTRIBUTES) {
            if (!fetched.asked(path)) {
                continue
            }
            if (!fetched.available(path)) {
                (unavailable ?: LinkedHashSet<String>().also { unavailable = it }) += path
            } else if (fetched.value(path) == true) {
                return Blacklist.BLOCKED
            }
        }
        return unavailable?.let { Blacklist(false, Cause.AttributeUnavailable(it, null)) } ?: Blacklist.NOT_BLOCKED
    }

    private class Blacklist(val blocked: Boolean, val unavailable: Cause?) {
        companion object {
            val BLOCKED = Blacklist(true, null)
            val NOT_BLOCKED = Blacklist(false, null)
        }
    }

    private fun SnapshotView.withUnresolved(cause: Cause): SnapshotView =
        SnapshotView(platform, app, tenant, unresolved + cause, version)

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

    private fun unavailable(requestId: String, traceId: String?, cause: Cause): Outcome =
        StatusMapping.unavailable(requestId, Unavailability.Indeterminate(cause), traceId)

    /**
     * The client's `CoSec-Request-Id` when it is a plain id ([RequestIds.isAcceptable]), else a new one. The id is
     * echoed into the response, logs and audit: an arbitrary value would let a client forge log lines or pose as
     * another request's correlation id.
     */
    private fun requestId(facts: HttpFacts): String {
        val given = facts.headers.values(CoSecParameters.REQUEST_ID).firstOrNull(RequestIds::isAcceptable)
        return given ?: idGenerator?.generateAsString() ?: RandomRequestIds.next()
    }

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

    private inline fun <T : Any> Fetched<T>.valueOr(failed: (Throwable) -> T): T = when (this) {
        is Fetched.Value -> value
        is Fetched.Failed -> failed(error)
    }

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
