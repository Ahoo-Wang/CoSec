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

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.runtime.ratelimit.LocalRateLimiter
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import tools.jackson.databind.JsonNode
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * A real [Pipeline] for an application's tests, built from its own policy documents: the same compiler, snapshot
 * source and pipeline as a starter-assembled host, with the only stand-in being the credential check, so that a test
 * says who the principal is without issuing a token. It gives an [evaluate] that answers as production answers (status,
 * problem, headers, `CoSec-Identity`), and the [securityContext] of a request the pipeline permitted, for the code
 * under test to read or to check more permissions with `AuthorizationChecker(fixture.pipeline)`.
 *
 * With `routes`, requests get the actions and resources of a route map (`RouteMapCodec.load`), as a host with
 * `cosec.authorization.routes.location` does. With `listeners`, each evaluation is delivered to them as a host delivers
 * it: a `REQUIRED` listener has the event before [evaluate] returns, a `BEST_EFFORT` one by the time [close] returns,
 * which delivers what is queued. So a `DecisionListener` is tested with the events the pipeline itself makes.
 *
 * It holds a snapshot source and the listeners: close it (`use { }`), one per test class.
 */
@ExperimentalCoSecApi
class PipelineFixture private constructor(
    /** The pipeline itself, for an `AuthorizationChecker` or a host adapter under test. */
    val pipeline: Pipeline,
    private val principals: MutableMap<String, Principal>,
    private val close: () -> Unit,
    /** False when the application's own authenticator decides who a request is: the fixture then issues no token. */
    private val issuesTokens: Boolean = true,
) : AutoCloseable {
    private val tokens = AtomicLong()

    /**
     * Who a request is authenticated as: [tenant] (default the default tenant), the [roles] of the default space or
     * `role@space`, the [policies] attached to it, the [app] its credential declares, and signed [attributes].
     * Equivalent to what a token would carry; not an API key, so a break-glass or API key cap does not apply.
     */
    class Principal @JvmOverloads constructor(
        val id: String,
        val tenant: String? = null,
        val roles: Set<String> = emptySet(),
        val policies: Set<String> = emptySet(),
        val app: String? = null,
        val attributes: Map<String, Any> = emptyMap(),
    ) {
        internal fun tenant(): TenantId = tenant?.let(::TenantId) ?: TenantId.DEFAULT

        /** The memberships a token would carry: `role` in the default space, `role@space` in that space. */
        internal fun memberships(): Memberships {
            val bySpace = roles.groupBy(
                { SpaceId(it.substringAfter('@', SpaceId.DEFAULT.value)) },
                { RoleId(it.substringBefore('@')) },
            ).mapValues { (_, roles) -> roles.toSet() }
            return Memberships(Memberships.Source.CLAIMS, bySpace, policies.map(::PolicyId))
        }
    }

    /**
     * A request as an adapter would hand it to the pipeline. With a [principal] it carries a credential for it, without
     * one it is anonymous; [hostApp] is the app of the host (`cosec.authorization.scope.app-id`), none by default.
     */
    @JvmOverloads
    fun request(
        method: String,
        path: String,
        principal: Principal? = null,
        query: String? = null,
        headers: Map<String, String> = emptyMap(),
        hostApp: String? = null,
    ): HttpFacts {
        val all = headers.mapValues { listOf(it.value) }.toMutableMap()
        principal?.let {
            check(issuesTokens) { OWN_AUTHENTICATOR }
            val token = "fixture-${tokens.incrementAndGet()}"
            principals[token] = it
            all["Authorization"] = listOf(BEARER + token)
        }
        return HttpFacts(
            method = method,
            rawPath = path,
            rawQuery = query,
            headers = Headers.of(all),
            cookies = Cookies.EMPTY,
            remoteAddress = REMOTE_ADDRESS,
            host = HostScope(hostApp?.let(::AppId)),
        )
    }

    /**
     * The `Authorization` header value of a request by [principal], for a request a test builds itself: one sent with
     * `WebTestClient` or `MockMvc` through CoSec's filter over [pipeline] (`CoSecTestBindings.filter`), as [request]
     * builds one for [evaluate].
     */
    fun authorization(principal: Principal): String {
        check(issuesTokens) { OWN_AUTHENTICATOR }
        val token = "fixture-${tokens.incrementAndGet()}"
        principals[token] = principal
        return BEARER + token
    }

    /** What the pipeline answers for [facts]. */
    suspend fun evaluate(facts: HttpFacts): Outcome = pipeline.evaluate(facts)

    /**
     * The [SecurityContext] of the request [facts] as a host handler would read it, in progress until [end] ends it.
     * Fails with an [AssertionError] naming the status and the problem when the pipeline did not permit the request.
     */
    suspend fun securityContext(facts: HttpFacts): SecurityContext {
        val outcome = pipeline.evaluate(facts)
        if (!outcome.proceed) {
            throw AssertionError("The pipeline refused the request: ${outcome.status} ${outcome.problem?.type?.name}")
        }
        return outcome.security!!.securityContext
    }

    /**
     * Ends the request of [context], as a host does when its response is complete (`PIP-26`): from then on every check
     * of it is denied, as for code that keeps a context past its request. An [Outcome] of [evaluate] ends its request
     * when it is closed, as a transport of the application's own closes it. Idempotent.
     *
     * @throws IllegalArgumentException when [context] is not one the pipeline made
     */
    fun end(context: SecurityContext) {
        requireNotNull(RequestSecurity.of(context)) { "The context [$context] is not one the pipeline made." }.end()
    }

    override fun close() = close.invoke()

    companion object {
        private const val OWN_AUTHENTICATOR = "This fixture decides who a request is with the application's own " +
            "authenticator (PipelineFixture.of(authenticator = ...)): it issues no token for a Principal. Send that " +
            "authenticator's credentials in the request's headers instead."
        private const val BEARER = "Bearer "
        private const val REMOTE_ADDRESS = "10.0.0.1"
        private val PREWARM_TIMEOUT = 30.seconds

        /**
         * The documents in [directory], laid out as [PolicyTestKit.load] reads them, with the route map [routes] and the
         * decision [listeners]. Throws [DocumentException] when a document does not compile. Rate limit rules count
         * their permits in [rateLimiter], a [LocalRateLimiter] of the fixture's own as the starter's default is, so a
         * request over a rule's limit answers `429`; pass one with a clock of the test's to cross a window.
         *
         * [authenticator], when given, decides who a request is, in place of the fixture's tokens: a suite with its own
         * token convention, or one whose requests already carry real credentials, adopts the fixture as it is. The
         * fixture then issues no token, so [request] with a principal and [authorization] throw.
         */
        @JvmStatic
        @JvmOverloads
        fun load(
            directory: Path,
            routes: RouteMap = RouteMap.EMPTY,
            listeners: List<DecisionListener> = emptyList(),
            rateLimiter: RateLimiter = LocalRateLimiter(),
            authenticator: Authenticator? = null,
        ): PipelineFixture = assemble(PolicyTestKit.documents(directory), routes, listeners, rateLimiter, authenticator)

        /**
         * The documents as JSON text: [apps] and [tenants] by id, with the route map [routes] and the decision
         * [listeners]. Throws [DocumentException] when one does not compile. Rate limits count in [rateLimiter], and
         * [authenticator] decides who a request is, as [load]'s do.
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            platform: String? = null,
            apps: Map<String, String> = emptyMap(),
            tenants: Map<String, String> = emptyMap(),
            routes: RouteMap = RouteMap.EMPTY,
            listeners: List<DecisionListener> = emptyList(),
            rateLimiter: RateLimiter = LocalRateLimiter(),
            authenticator: Authenticator? = null,
        ): PipelineFixture = assemble(
            ScenarioDocuments(
                platform?.let(ScenarioJson::parse),
                apps.mapValues { ScenarioJson.parse(it.value) },
                tenants.mapValues { ScenarioJson.parse(it.value) },
            ),
            routes,
            listeners,
            rateLimiter,
            authenticator,
        )

        /**
         * A fixture whose documents cannot be read, as a host whose store of record is down from its start: every read
         * fails, no document is ever installed, and every request the pipeline decides answers `503 unavailable`, never
         * a permit, whoever the principal. For a test that proves an application's routes fail closed. With the route
         * map [routes] and the decision [listeners], as [of].
         */
        @JvmStatic
        @JvmOverloads
        fun unreadable(
            routes: RouteMap = RouteMap.EMPTY,
            listeners: List<DecisionListener> = emptyList(),
        ): PipelineFixture {
            val principals = ConcurrentHashMap<String, Principal>()
            val pipeline = Pipeline.builder(fixtureAuthenticator(principals), UnreadableDocuments)
                .routes(routes)
                .apply { listeners.forEach(::listener) }
                .build()
            return PipelineFixture(pipeline, principals, pipeline::close)
        }

        private fun assemble(
            documents: ScenarioDocuments,
            routes: RouteMap,
            listeners: List<DecisionListener>,
            rateLimiter: RateLimiter,
            authenticator: Authenticator?,
        ): PipelineFixture {
            // Compiled here first, so that a document that cannot compile fails the test, not its first request.
            DocumentSnapshotCompiler.stored.compile(documents)
            val store = InMemorySnapshotDocumentStore()
            store.put(SnapshotKey.Platform, published(documents.platform))
            documents.apps.forEach { (id, body) -> store.put(SnapshotKey.App(id), published(body)) }
            documents.tenants.forEach { (id, body) -> store.put(SnapshotKey.Tenant(id), published(body)) }

            val principals = ConcurrentHashMap<String, Principal>()
            val pipeline = Pipeline.builder(authenticator ?: fixtureAuthenticator(principals), store)
                .snapshots {
                    prewarmApps = documents.apps.keys
                    prewarmTenants = documents.tenants.keys
                }
                .routes(routes)
                .rateLimiter(rateLimiter)
                .apply { listeners.forEach(::listener) }
                .build()
            // Installed before the first evaluate, so a test's first request is decided as any later one.
            val missing = try {
                runBlocking { withTimeout(PREWARM_TIMEOUT) { pipeline.awaitDocuments() } }
            } catch (e: TimeoutCancellationException) {
                pipeline.close()
                throw IllegalStateException("The scenario documents were not installed within $PREWARM_TIMEOUT.", e)
            }
            if (missing.isNotEmpty()) {
                pipeline.close()
                error("The scenario documents $missing cannot be installed: see the log for why.")
            }
            return PipelineFixture(pipeline, principals, pipeline::close, issuesTokens = authenticator == null)
        }

        /** Answers the token a [request] wrote; a request with any other token is anonymous, never authenticated. */
        /** How the fixture authenticates: a token it issued names a [Principal]; with no credential of its own. */
        internal fun fixtureAuthenticator(principals: Map<String, Principal>) = Authenticator { credentials ->
            val principal = credentials.token?.removePrefix(BEARER)?.let(principals::get)
            if (principal == null) {
                AuthnResult.Anonymous
            } else {
                AuthnResult.Authenticated(authenticated(principal), Identity.Method("testkit"))
            }
        }

        private fun authenticated(principal: Principal): AuthenticatedPrincipal =
            AuthenticatedPrincipal(
                PrincipalId(principal.id),
                principal.tenant(),
                principal.memberships(),
                principal.attributes,
                app = principal.app?.let(::AppId),
            )

        private fun published(body: JsonNode?): SnapshotDocument = DocumentSnapshotCompiler.published(body)
    }
}

/** A store of record that cannot be reached: every read fails as an unreachable store's does, never as a format error. */
private object UnreadableDocuments : SnapshotDocumentStore {
    override fun read(key: SnapshotKey): CompletableFuture<SnapshotRead> = unreachable(key)

    override fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?): CompletableFuture<SnapshotRead> =
        unreachable(key)

    override fun subscribe(listener: SnapshotChangeListener): AutoCloseable = AutoCloseable { }

    private fun unreachable(key: SnapshotKey): CompletableFuture<SnapshotRead> = CompletableFuture.failedFuture(
        IllegalStateException("$key cannot be read: the test kit's store is unreadable (PipelineFixture.unreadable).")
    )
}
