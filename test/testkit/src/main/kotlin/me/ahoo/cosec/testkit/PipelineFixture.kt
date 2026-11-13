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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshotViews
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.runtime.snapshot.PlatformCompiler
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import tools.jackson.databind.JsonNode
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A real [Pipeline] for an application's tests, built from its own policy documents: the same compiler, snapshot
 * source and pipeline as a starter-assembled host, with the only stand-in being the credential check, so that a test
 * says who the principal is without issuing a token. It gives an [evaluate] that answers as production answers (status,
 * problem, headers, `CoSec-Identity`), and the [securityContext] of a request the pipeline permitted, for the code
 * under test to read or to check more permissions with `AuthorizationChecker(fixture.pipeline)`.
 *
 * It holds a snapshot source: close it (`use { }`), one per test class.
 */
@ExperimentalCoSecApi
class PipelineFixture private constructor(
    /** The pipeline itself, for an `AuthorizationChecker` or a host adapter under test. */
    val pipeline: Pipeline,
    private val principals: MutableMap<String, Principal>,
    private val close: () -> Unit,
) : AutoCloseable {
    private val tokens = AtomicLong()

    /**
     * Who a request is authenticated as: [tenant] (default the default tenant), the [roles] of the default space or
     * `role@space`, the [policies] attached to it, the [app] its credential declares, and signed [attributes].
     * Equivalent to what a token would carry; not an API key, so a break-glass or API key cap does not apply.
     */
    class Principal(
        val id: String,
        val tenant: String? = null,
        val roles: Set<String> = emptySet(),
        val policies: Set<String> = emptySet(),
        val app: String? = null,
        val attributes: Map<String, Any> = emptyMap(),
    )

    /**
     * A request as an adapter would hand it to the pipeline. With a [principal] it carries a credential for it, without
     * one it is anonymous; [hostApp] is the app of the host (`cosec.authorization.scope.app-id`), none by default.
     */
    @Suppress("LongParameterList")
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

    /** What the pipeline answers for [facts]. */
    suspend fun evaluate(facts: HttpFacts): Outcome = pipeline.evaluate(facts)

    /**
     * The [SecurityContext] of the request [facts] as a host handler would read it. Fails with an [AssertionError]
     * naming the status and the problem when the pipeline did not permit the request.
     */
    suspend fun securityContext(facts: HttpFacts): SecurityContext {
        val outcome = pipeline.evaluate(facts)
        if (!outcome.proceed) {
            throw AssertionError("The pipeline refused the request: ${outcome.status} ${outcome.problem?.type?.name}")
        }
        return outcome.security!!.securityContext
    }

    override fun close() = close.invoke()

    companion object {
        private const val BEARER = "Bearer "
        private const val REMOTE_ADDRESS = "10.0.0.1"
        private const val PREWARM_SECONDS = 30L

        /**
         * The documents in [directory], laid out as [PolicyTestKit.load] reads them. Throws [DocumentException] when a
         * document does not compile.
         */
        fun load(directory: Path): PipelineFixture = assemble(PolicyTestKit.documents(directory))

        /** The documents as JSON text: [apps] and [tenants] by id. Throws [DocumentException] when one does not compile. */
        fun of(
            platform: String? = null,
            apps: Map<String, String> = emptyMap(),
            tenants: Map<String, String> = emptyMap(),
        ): PipelineFixture = assemble(
            ScenarioDocuments(
                platform?.let(ScenarioJson::parse),
                apps.mapValues { ScenarioJson.parse(it.value) },
                tenants.mapValues { ScenarioJson.parse(it.value) },
            ),
        )

        private fun assemble(documents: ScenarioDocuments): PipelineFixture {
            // Compiled here first, so that a document that cannot compile fails the test, not its first request.
            DocumentSnapshotCompiler.stored.compile(documents)
            val store = InMemorySnapshotDocumentStore()
            store.put(SnapshotKey.Platform, published(documents.platform))
            documents.apps.forEach { (id, body) -> store.put(SnapshotKey.App(id), published(body)) }
            documents.tenants.forEach { (id, body) -> store.put(SnapshotKey.Tenant(id), published(body)) }

            val compiler = PlatformCompiler()
            val source = DocumentSnapshots.source(store, compiler, SnapshotOptions())
            compiler.bind(source)
            val keys = listOf<SnapshotKey>(SnapshotKey.Platform) +
                documents.apps.keys.map { SnapshotKey.App(it) } +
                documents.tenants.keys.map { SnapshotKey.Tenant(it) }
            // Installed before the first evaluate, so a test's first request is decided as any later one.
            source.prewarm(keys).get(PREWARM_SECONDS, TimeUnit.SECONDS)
            val principals = ConcurrentHashMap<String, Principal>()
            val pipeline = Pipeline(
                PipelineComponents(
                    authenticator = fixtureAuthenticator(principals),
                    snapshots = SuspendingSnapshotSource(DocumentSnapshotViews(source, platformRequired = true)),
                ),
            )
            return PipelineFixture(pipeline, principals, source::close)
        }

        /** Answers the token a [request] wrote; a request with any other token is anonymous, never authenticated. */
        private fun fixtureAuthenticator(principals: Map<String, Principal>) = Authenticator { credentials ->
            val principal = credentials.token?.removePrefix(BEARER)?.let(principals::get)
            if (principal == null) {
                AuthnResult.Anonymous
            } else {
                AuthnResult.Authenticated(authenticated(principal), "testkit")
            }
        }

        private fun authenticated(principal: Principal): AuthenticatedPrincipal {
            val bySpace = principal.roles.groupBy(
                { SpaceId(it.substringAfter('@', SpaceId.DEFAULT.value)) },
                { RoleId(it.substringBefore('@')) },
            ).mapValues { (_, roles) -> roles.toSet() }
            return AuthenticatedPrincipal(
                PrincipalId(principal.id),
                principal.tenant?.let(::TenantId) ?: TenantId.DEFAULT,
                Memberships(Memberships.Source.CLAIMS, bySpace, principal.policies.map(::PolicyId)),
                principal.attributes,
                app = principal.app?.let(::AppId),
            )
        }

        private fun published(body: JsonNode?): SnapshotDocument = DocumentSnapshotCompiler.published(body)
    }
}
