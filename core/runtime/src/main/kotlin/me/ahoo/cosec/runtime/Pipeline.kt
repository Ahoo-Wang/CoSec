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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.call.Answer
import me.ahoo.cosec.runtime.call.FallbackIds
import me.ahoo.cosec.runtime.call.callPort
import me.ahoo.cosec.runtime.call.resumeOffIo
import me.ahoo.cosec.runtime.call.withLazyTimeoutOrNull
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.http.CookieHeaders
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.QueryParameters
import me.ahoo.cosec.runtime.http.RequestGate
import me.ahoo.cosec.runtime.http.RequestIds
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.runtime.http.StatusMapping
import me.ahoo.cosec.runtime.http.TraceContext
import me.ahoo.cosec.runtime.http.Unavailability
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.runtime.snapshot.DocumentReadiness
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.stage.AttributeFetching
import me.ahoo.cosec.runtime.stage.Authenticate
import me.ahoo.cosec.runtime.stage.DeliverRequired
import me.ahoo.cosec.runtime.stage.Evaluate
import me.ahoo.cosec.runtime.stage.FetchAttributes
import me.ahoo.cosec.runtime.stage.ForwardIdentity
import me.ahoo.cosec.runtime.stage.LoadView
import me.ahoo.cosec.runtime.stage.MembershipResolver
import me.ahoo.cosec.runtime.stage.PublishBestEffort
import me.ahoo.cosec.runtime.stage.ReadRequest
import me.ahoo.cosec.runtime.stage.RequestState
import me.ahoo.cosec.runtime.stage.ResolveMembers
import me.ahoo.cosec.runtime.stage.ResolveScope
import me.ahoo.cosec.runtime.stage.Respond
import me.ahoo.cosec.runtime.stage.RunObligations
import me.ahoo.cosec.runtime.stage.Stage
import me.ahoo.cosec.runtime.stage.requestPathLog
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import kotlin.coroutines.cancellation.CancellationException

/**
 * The request pipeline (RFC 0006 §5.8): one suspend function from [HttpFacts] to [Outcome], which adapters write.
 *
 * Within `cosec.pipeline.timeout`, the [stages] in order (RFC 0013 §4.2): read the request (400 when ambiguous) →
 * [Authenticator] → memberships, together with the stage-A attributes such as the blacklist → scope → snapshot view →
 * the stage-B attributes → engine → rate-limit obligations, after a permit only → [StatusMapping] (§5.4). Then, on
 * the outcome: the forwarded identity at a gateway, the REQUIRED decision listeners, the BEST_EFFORT ones (§9).
 *
 * Fail closed, structurally: only a [Decision.Permit] makes an outcome that proceeds. Each SPI call that fails, throws
 * or does not answer in time is a 503; an exception nothing modelled is a 500; and an external cancellation makes no
 * outcome at all (§7.4, `PIP-3`). Every SPI call goes through [resumeOffIo], so a request that never really suspends
 * finishes on its calling thread without a dispatch (§7.2).
 */
class Pipeline internal constructor(
    components: PipelineComponents,
    /** What [close] closes, in order: what the builder created for this pipeline. */
    private val owned: List<AutoCloseable> = emptyList(),
    /** The documents this pipeline loaded at build; null when its snapshot source is its owner's. */
    private val readiness: DocumentReadiness? = null,
) : RequestGate, AutoCloseable {
    private val snapshots: SuspendingSnapshotSource = components.snapshots
    private val engine = components.engine
    private val clock = components.clock
    private val options = components.options

    /** The application's generator, falling back to a random id when it throws (round 2 M6). */
    private val ids = FallbackIds(components.idGenerator)
    private val fetching = AttributeFetching(components.attributes, engine, options.attributeTimeout)
    private val memberships = MembershipResolver(components.membershipProvider, options.membershipTimeout)

    /** The request path, in order (`StageOrderTest`): each stage ends the request or lets the next one run. */
    internal val stages: Array<Stage> = arrayOf(
        ReadRequest(components.routes),
        Authenticate(
            components.authenticator,
            options.authenticationTimeout,
            options.apiKeyHeader,
            options.allowQueryToken,
            options.allowCookieToken,
        ),
        ResolveMembers(fetching, memberships),
        ResolveScope,
        LoadView(snapshots),
        FetchAttributes(fetching, clock, options.apiKeyHeader),
        Evaluate(engine, options.traceLevel, components.listeners),
        RunObligations(components.rateLimiter, options.rateLimitTimeout),
        Respond,
    )
    private val forwardIdentity = components.identity?.let(::ForwardIdentity)
    private val deliverRequired = DeliverRequired(components.listeners, ids, options.timeout)
    private val publishBestEffort = PublishBestEffort(components.listeners, ids)

    /**
     * The request headers this pipeline reads a credential from: `Authorization` and the API key header
     * (`cosec.pipeline.api-key-header`). With the `authorization` query parameter ([withoutQueryToken]) and the
     * `authorization` cookie ([withoutCookieToken]), what a gateway keeps from a route that may not receive the
     * caller's credentials (RC1-5, R1-4 of #1194's review): a credential the pipeline accepts is one a backend could
     * replay at the gateway.
     */
    @InternalCoSecApi
    val credentialHeaders: Set<String> = setOf(Authenticate.AUTHORIZATION, options.apiKeyHeader)

    /**
     * [rawQuery] without its `authorization` parameters, however their names are encoded or cased; null when nothing is
     * left, and [rawQuery] itself when it has none (RC1-5). Whether or not this pipeline reads a token there
     * (`cosec.pipeline.allow-query-token`), a backend might.
     */
    @InternalCoSecApi
    fun withoutQueryToken(rawQuery: String?): String? = QueryParameters.without(rawQuery, Authenticate.AUTHORIZATION)

    /**
     * [cookieHeader], one `Cookie` header value, without its `authorization` cookies, however their names are cased;
     * null when no cookie is left, and [cookieHeader] itself when it has none (R1-4 of #1194's review). Whether or not
     * this pipeline reads a token there (`cosec.pipeline.allow-cookie-token`), a backend might.
     */
    @InternalCoSecApi
    fun withoutCookieToken(
        cookieHeader: String
    ): String? = CookieHeaders.without(cookieHeader, Authenticate.AUTHORIZATION)

    /**
     * Whether [cookieHeader], one `Cookie` header value, has an `authorization` cookie [withoutCookieToken] removes:
     * exactly when it changes [cookieHeader]. Allocates nothing, so a gateway asks it of every header first.
     */
    @InternalCoSecApi
    fun namesCookieToken(cookieHeader: String): Boolean = CookieHeaders.names(cookieHeader, Authenticate.AUTHORIZATION)

    /**
     * The only entry point of the adapters. An external cancellation propagates unchanged and makes no outcome;
     * everything else, a defect included, becomes one. An [Error] propagates too: the host answers it (§7.4).
     */
    @ExperimentalCoSecApi
    override suspend fun evaluate(facts: HttpFacts): Outcome {
        // The trace the bridge captured (§7.5): its id goes into problem bodies and decision events.
        val trace = currentCoroutineContext()[TraceContext]
        val state = RequestState(facts, requestId(facts), trace, System.nanoTime(), clock.millis())
        val outcome = try {
            decide(state)
        } catch (e: CancellationException) {
            // Only an external cancellation gets here: a CANCELLED event, and still no outcome (§7.4).
            publishBestEffort.run(state, null)
            throw e
        }
        publishBestEffort.run(state, outcome)
        return outcome
    }

    /** The stages within the deadline, then the identity and the REQUIRED listeners on their outcome. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun decide(state: RequestState): Outcome = try {
        // A ...OrNull timeout, never withTimeout: its TimeoutCancellationException would look like an external
        // cancellation below and vanish instead of answering 503 (RFC 0006 K13). Lazy: a request that never
        // suspends arms no timer.
        val decided = withLazyTimeoutOrNull(options.timeout) { runStages(state) }
            ?: StatusMapping.unavailable(state.requestId, Unavailability.DeadlineExceeded, state.traceId)
        val forwarded = forwardIdentity?.run(state, decided) ?: decided
        deliverRequired.run(state, forwarded)
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) {
            throw e
        }
        // Nobody cancelled this request: something threw a CancellationException of its own.
        requestPathLog.error(e) { "Request [${state.requestId}] failed with a stray cancellation." }
        StatusMapping.error(state.requestId, e, state.traceId)
    } catch (e: Exception) {
        requestPathLog.error(e) { "Request [${state.requestId}] failed with an unmodelled exception." }
        StatusMapping.error(state.requestId, e, state.traceId)
    }

    private suspend fun runStages(state: RequestState): Outcome {
        for (stage in stages) {
            stage.run(state)?.let { return it }
        }
        error("Respond always answers.")
    }

    /** What the membership provider answers for [principal] now, capped as its credential is; null when it does not know it. */
    internal suspend fun currentMemberships(principal: AuthenticatedPrincipal): Memberships? =
        memberships.resolve(principal.tenant, principal.id, principal.membershipSource)

    /**
     * The decision for [action] on [resource] as [security] (RFC 0006 §7.6): the view, the attributes and the engine of
     * the request path, for the principal and scope of a permitted request. A query, not a request: no obligation runs,
     * no listener hears of it, and nothing fails open: an unreadable view, an error or a permit only break-glass gives
     * is not a permit.
     */
    internal suspend fun check(security: RequestSecurity, action: Action, resource: Resource): Decision {
        val view = when (val loaded = callPort(null) { snapshots.view(security.scope, false) }) {
            is Answer.Value -> loaded.value
            is Answer.Failed -> return Decision.Indeterminate(
                Cause.SourceUnavailable(DocumentKind.PLATFORM, loaded.error)
            )
        }
        val authz = with(security.request) { AuthzRequest(subject, action, resource, environment) }
        val request = FlatRequest(authz, security.scope, security.memberships, clock.millis(), security.attributes)
        // A new action and resource: what the request fetched was for its own. Every attribute this one needs is
        // fetched now, from stage A as from stage B (#1209).
        val evaluated = fetching.withPlanned(request, view, fetching.fetched(), everyStage = true)
        val decision = engine.evaluate(evaluated, view, options.traceLevel).decision
        // A check writes no audit, and every use of break-glass is written to a REQUIRED one (§9.4.3): a check that
        // only break-glass permits is refused (review A1).
        if (decision is Decision.Permit && view.platform.isBreakGlass(decision.by.via)) {
            return Decision.Indeterminate(Cause.RequiredListenerFailed(emptyList()))
        }
        return decision
    }

    /**
     * The client's `CoSec-Request-Id` when it is a plain id ([RequestIds.isAcceptable]), else a new one. The id is
     * echoed into the response, logs and audit: an arbitrary value would let a client forge log lines or pose as
     * another request's correlation id.
     */
    private fun requestId(facts: HttpFacts): String {
        val given = facts.headers.values(CoSecParameters.REQUEST_ID).firstOrNull(RequestIds::isAcceptable)
        return given ?: ids.generate()
    }

    /**
     * Changes no decision: a request for a document that is not installed is a 503 whether or not anyone waits here.
     * Suspends until the platform document and the prewarmed ones ([SnapshotSettings.prewarmApps],
     * [SnapshotSettings.prewarmTenants]) have been read once, and answers those that are not installed: an empty set
     * means ready. Cancellable; the loads go on. A host's readiness probe asks it, under a timeout of its own.
     *
     * @throws IllegalStateException for a pipeline over a snapshot source its owner keeps: the owner decides readiness.
     */
    @ExperimentalCoSecApi
    suspend fun awaitDocuments(): Set<SnapshotKey> = checkNotNull(readiness) {
        "This pipeline reads a snapshot source its owner keeps; the owner of the source decides readiness."
    }.await()

    /** Closes what the builder created for this pipeline: drains the listeners' queues, then closes the documents. */
    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        var failure: Exception? = null
        for (closeable in owned) {
            try {
                closeable.close()
            } catch (e: Exception) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        failure?.let { throw it }
    }

    companion object {
        /**
         * A pipeline over the documents [documentStore] holds (RFC 0013 §4.3): it compiles and installs them as a Spring
         * host does, and [close] closes them. Load the ones a host needs before it takes traffic with
         * [PipelineBuilder.snapshots], and wait for them with [awaitDocuments].
         */
        @ExperimentalCoSecApi
        fun builder(authenticator: Authenticator, documentStore: SnapshotDocumentStore): PipelineBuilder =
            PipelineBuilder(authenticator, documentStore, null)

        /** A pipeline over [snapshots], which its owner keeps, loads and closes: for CoSec's own hosts. */
        @InternalCoSecApi
        fun builder(authenticator: Authenticator, snapshots: SuspendingSnapshotSource): PipelineBuilder =
            PipelineBuilder(authenticator, null, snapshots)

        /**
         * The gate of a service behind a CoSec gateway (RFC 0009 §5): it runs no pipeline and accepts the identity the
         * gateway verified and forwarded, as [settings] bound it.
         */
        @ExperimentalCoSecApi
        fun behindGateway(settings: GatewayIdentitySettings.() -> Unit = {}): RequestGate {
            val identity = GatewayIdentitySettings().apply(settings)
            return IdentityGate(identity.maxBytes, identity.maxAge, identity.skew, identity.clock, identity.ids)
        }
    }
}
