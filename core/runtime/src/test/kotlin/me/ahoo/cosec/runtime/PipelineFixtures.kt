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

import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.id.IdGenerator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/** Test doubles for the pipeline: every SPI it calls, with no I/O unless a test adds it. */
object PipelineFixtures {
    const val GENERATED_ID = "generated-id"
    val TENANT = TenantId("tenant-a")
    val PRINCIPAL = PrincipalId("alice")

    val PERMIT: Decision = Decision.Permit(
        StatementRef.Policy(PolicyId("orders"), 0, "read", BindingRef.Attached(PolicyId("orders")))
    )
    val EXPLICIT_DENY: Decision = Decision.Deny(
        StatementRef.Policy(
            PolicyId("secret-policy"),
            3,
            "secret-statement",
            BindingRef.Attached(PolicyId("secret-policy"))
        )
    )
    val IMPLICIT_DENY: Decision = Decision.Deny(null)

    val ids = IdGenerator { GENERATED_ID }

    fun facts(
        path: String = "/orders",
        query: String? = null,
        headers: Map<String, List<String>> = emptyMap(),
        cookies: Map<String, List<String>> = emptyMap(),
        host: HostScope = HostScope(null),
    ): HttpFacts = HttpFacts(
        method = "GET",
        rawPath = path,
        rawQuery = query,
        headers = Headers.of(headers),
        cookies = Cookies.of(cookies),
        remoteAddress = "10.0.0.1",
        host = host,
    )

    fun claims(vararg roles: Pair<String, String>): Memberships = Memberships(
        Memberships.Source.CLAIMS,
        roles.groupBy({ SpaceId(it.first) }, { RoleId(it.second) }).mapValues { it.value.toSet() },
        emptyList(),
    )

    fun authenticated(memberships: Memberships? = claims()): AuthnResult =
        AuthnResult.Authenticated(AuthenticatedPrincipal(PRINCIPAL, TENANT, memberships), "test")

    fun authenticator(result: AuthnResult): Authenticator = Authenticator { result }

    val ANONYMOUS: Authenticator = authenticator(AuthnResult.Anonymous)

    /** Every document installed and empty. */
    class InstalledViews : SnapshotViews {
        val loads = CopyOnWriteArrayList<ViewKey>()

        override fun peek(key: ViewKey): SnapshotView = viewFor(key)

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> {
            loads += key
            return CompletableFuture.completedFuture(viewFor(key))
        }
    }

    /** Nothing installed: every view is loaded through [loader]. */
    class LoadingViews(private val loader: (ViewKey) -> CompletableFuture<SnapshotView>) : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView? = null

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = loader(key)
    }

    fun viewFor(key: ViewKey): SnapshotView = SnapshotView(
        CompiledDocument.platform(emptyList(), emptyList()),
        key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
        key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
    )

    /** Answers [decision] and records what it was asked, and on which thread. */
    class StubEngine(private val decision: () -> Decision) : FlatEngine {
        constructor(decision: Decision) : this({ decision })

        val requests = CopyOnWriteArrayList<FlatRequest>()
        val threads = CopyOnWriteArrayList<Thread>()

        override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel): FlatEvaluation {
            requests += request
            threads += Thread.currentThread()
            return FlatEvaluation(decision(), null)
        }
    }

    /** A pipeline of [authenticator], [engine] and [views] fixed ids; [configure] sets any other component. */
    fun pipeline(
        authenticator: Authenticator = ANONYMOUS,
        engine: FlatEngine = StubEngine(PERMIT),
        views: SnapshotViews = InstalledViews(),
        configure: (PipelineComponents) -> PipelineComponents = { it },
    ): Pipeline = Pipeline(
        PipelineComponents(
            authenticator = authenticator,
            snapshots = SuspendingSnapshotSource(views),
            engine = engine,
            idGenerator = ids,
        ).let(configure)
    )
}
