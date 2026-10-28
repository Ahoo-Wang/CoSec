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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotStaleException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** The 8.0 source behind the pipeline's views: real store, real compiler, real engine. */
class DocumentSnapshotViewsTest {
    private val store = InMemorySnapshotDocumentStore()
    private val revisions = HashMap<SnapshotKey, Long>()

    private fun publish(key: SnapshotKey, body: String) {
        val revision = (revisions[key] ?: 0) + 1
        revisions[key] = revision
        val text = """{"schema":2,"revision":$revision,"publishedAt":"2026-10-26T00:00:00Z"$body}"""
        store.put(key, SnapshotDocument(2, revision, Instant.parse("2026-10-26T00:00:00Z"), text.encodeToByteArray()))
    }

    private fun source() = DocumentSnapshots.source(
        store,
        PolicyDocumentCompiler(),
        SnapshotOptions(revalidateInterval = Duration.ZERO)
    )

    private fun scope(tenant: String, roles: List<String> = emptyList(), app: String = "order"): Scope {
        val memberships =
            Memberships(
                Memberships.Source.CLAIMS,
                mapOf(SpaceId.DEFAULT to roles.mapTo(LinkedHashSet(), ::RoleId)),
                emptyList()
            )
        return (
            ScopeResolver.resolve(
                ScopeSubject(TenantId(tenant), true, memberships),
                DeclaredScope(app, ""),
                HostScope(null)
            ) as ScopeResolution.Resolved
            ).scope
    }

    private fun decide(view: SnapshotView, scope: Scope, action: String = "order:read"): Decision {
        val authz = AuthzRequest(
            Subject("alice", scope.tenant.value, true, emptySet(), emptySet(), emptyMap(), false),
            me.ahoo.cosec.model.Scope.declared(scope.tenant.value, scope.app.value, ""),
            Action(action.substringBefore(':'), action.substringAfter(':')),
            Resource("order", "1", emptyMap()),
            Environment("10.0.0.1", "r", "d", blocked = false),
        )
        val memberships =
            Memberships(Memberships.Source.CLAIMS, mapOf(SpaceId.DEFAULT to scope.roles.toSet()), emptyList())
        return CoSecFlatEngine.evaluate(FlatRequest(authz, scope, memberships, 0), view).decision
    }

    private val allowRead = ""","policies":[{"id":"read","statements":[{"name":"read","effect":"allow","action":"order:read"}]}]"""

    @Test
    fun `a view composes the platform, the tenant and the app the request needs`() {
        publish(SnapshotKey.Platform, allowRead)
        publish(
            SnapshotKey.Tenant("acme"),
            ""","bindings":[{"id":"t","subject":"*","app":"*","space":"*","policy":"read"}]"""
        )
        publish(
            SnapshotKey.App("order"),
            ""","policies":[{"id":"perm:order/cancel","statements":[{"effect":"allow","action":"order:cancel"}]}],
               "bindings":[{"id":"r","subject":{"role":"clerk"},"app":"order","space":"*","policy":"perm:order/cancel"}]""",
        )
        source().use { source ->
            val views = DocumentSnapshotViews(source)
            val scope = scope("acme", listOf("clerk"))
            val key = ViewKey.of(scope, blocked = false)
            views.peek(key).assert().isNull()
            val view = views.load(key).join()
            views.peek(key).assert().isNotNull()
            decide(view, scope).assert().isInstanceOf(Decision.Permit::class.java)
            decide(view, scope, "order:cancel").assert().isInstanceOf(Decision.Permit::class.java)
            decide(view, scope, "order:delete").assert().isEqualTo(Decision.Deny(null))
        }
    }

    /** SNP-1: a view keeps the revisions it was built from, whatever is published while it decides. */
    @Test
    fun `a view decides with the revisions it was built from`() {
        publish(SnapshotKey.Platform, allowRead)
        publish(
            SnapshotKey.Tenant("acme"),
            ""","bindings":[{"id":"t","subject":"*","app":"*","space":"*","policy":"read"}]"""
        )
        source().use { source ->
            val views = DocumentSnapshotViews(source)
            val scope = scope("acme")
            val view = views.load(ViewKey.of(scope, blocked = false)).join()
            publish(SnapshotKey.Tenant("acme"), "")
            decide(view, scope).assert().isInstanceOf(Decision.Permit::class.java)
            // Once the new revision is installed, the next view decides with it.
            source.reload(SnapshotKey.Tenant("acme")).join().revision.assert().isEqualTo(2)
            decide(views.peek(ViewKey.of(scope, blocked = false))!!, scope).assert().isEqualTo(Decision.Deny(null))
        }
    }

    @Test
    fun `missing documents are empty, failed ones are unresolved`() {
        publish(SnapshotKey.Platform, allowRead)
        publish(SnapshotKey.Tenant("broken"), ""","policies":[{"id":"perm:x/y","statements":[]}]""")
        source().use { source ->
            val views = DocumentSnapshotViews(source)
            // No tenant document published yet: an empty one, so nothing binds the platform's policy.
            val fresh = scope("fresh")
            decide(views.load(ViewKey.of(fresh, blocked = false)).join(), fresh).assert().isEqualTo(Decision.Deny(null))
            val broken = scope("broken")
            val view = views.load(ViewKey.of(broken, blocked = false)).join()
            view.tenant.assert().isNull()
            (view.unresolved.single() as Cause.SourceUnavailable).document.assert().isEqualTo(DocumentKind.TENANT)
            decide(view, broken).assert().isInstanceOf(Decision.Indeterminate::class.java)
        }
    }

    @Test
    fun `a platform that fails still lets the blacklist decide`() {
        publish(SnapshotKey.Platform, ""","policies":[{"id":"perm:x/y","statements":[]}]""")
        source().use { source ->
            val views = DocumentSnapshotViews(source)
            val view = views.load(ViewKey.BLOCKED).join()
            view.platform.kind.assert().isEqualTo(DocumentKind.PLATFORM)
            (view.unresolved.single() as Cause.SourceUnavailable).document.assert().isEqualTo(DocumentKind.PLATFORM)
        }
    }

    @Test
    fun `a tenant id that cannot name a document is unresolved`() {
        publish(SnapshotKey.Platform, allowRead)
        source().use { source ->
            val views = DocumentSnapshotViews(source)
            val scope = scope("a:b")
            val view = views.load(ViewKey.of(scope, blocked = false)).join()
            view.tenant.assert().isNull()
            (view.unresolved.single() as Cause.SourceUnavailable).error.assert().isInstanceOf(
                IllegalArgumentException::class.java
            )
            views.peek(ViewKey.of(scope, blocked = false))!!.unresolved.assert().hasSize(1)
        }
    }

    @Test
    fun `where the platform is required, an absent one fails closed`() {
        publish(
            SnapshotKey.Tenant("acme"),
            ""","bindings":[{"id":"t","subject":"*","app":"*","space":"*","policy":"read"}]"""
        )
        source().use { source ->
            DocumentSnapshotViews(source).load(ViewKey.BLOCKED).join().unresolved.assert().isEmpty()
            val scope = scope("acme")
            val view = DocumentSnapshotViews(
                source,
                platformRequired = true
            ).load(ViewKey.of(scope, blocked = false)).join()
            (view.unresolved.single() as Cause.SourceUnavailable).error.assert().isInstanceOf(
                MissingPlatformException::class.java
            )
            decide(view, scope).assert().isInstanceOf(Decision.Indeterminate::class.java)
        }
    }

    @Test
    fun `instance documents compile against the platform's attributes, and again when they change`() {
        val readInFence = ""","policies":[{"id":"fenced","statements":[{"effect":"allow","action":"order:read",""" +
            """"condition":{"eq":["ext.geo.inFence",true]}}]}],"bindings":[{"id":"t","subject":"*","app":"*","space":"*","policy":"fenced"}]"""
        publish(SnapshotKey.Platform, "")
        publish(SnapshotKey.Tenant("acme"), readInFence)
        val compiler = PlatformCompiler()
        DocumentSnapshots.source(store, compiler, SnapshotOptions(revalidateInterval = Duration.ZERO)).use { source ->
            compiler.bind(source)
            val views = DocumentSnapshotViews(source, platformRequired = true)
            val key = ViewKey.of(scope("acme"), blocked = false)
            source.load().join()
            // ext.geo.inFence is declared nowhere yet: the tenant document does not compile.
            views.load(key).join().tenant.assert().isNull()
            publish(SnapshotKey.Platform, ""","attributes":[{"path":"ext.geo.inFence","type":"bool"}]""")
            source.reload(SnapshotKey.Platform).join()
            awaitCompiled(views, key)
            compiler.current.assert().isNotNull()
            // Unchanged limits and attributes recompile nothing.
            publish(SnapshotKey.Platform, ""","attributes":[{"path":"ext.geo.inFence","type":"bool"}]""")
            source.reload(SnapshotKey.Platform).join()
            views.load(key).join().tenant.assert().isNotNull()
        }
    }

    /** A clock the test moves: how long ago the source confirmed its documents. */
    private class MovingClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    /** RFC 0003 §4.5 (`SNP-5`): a fail-closed tenant whose documents lag more than its bound is not decided on. */
    @Test
    fun `a fail-closed tenant past its lag bound is unresolved, others decide`() {
        publish(
            SnapshotKey.Platform,
            allowRead + ""","bindings":[{"id":"read","subject":"*","app":"*","space":"*","policy":"read"}]"""
        )
        val clock = MovingClock(Instant.parse("2026-10-26T00:00:00Z"))
        val options = SnapshotOptions(
            revalidateInterval = Duration.ZERO,
            failClosedTenants = setOf("acme"),
            failClosedMaxLag = Duration.ofMinutes(5),
        )
        DocumentSnapshots.source(store, PolicyDocumentCompiler(), options, clock).use { source ->
            val views = DocumentSnapshotViews(source)
            val acme = ViewKey.of(scope("acme"), blocked = false)
            val beta = ViewKey.of(scope("beta"), blocked = false)
            views.load(acme).join()
            views.load(beta).join()

            clock.now = clock.now.plus(Duration.ofMinutes(5))
            views.peek(acme)!!.unresolved.assert().isEmpty()
            decide(views.peek(acme)!!, scope("acme")).assert().isInstanceOf(Decision.Permit::class.java)

            clock.now = clock.now.plusSeconds(1)
            val stale = views.peek(acme)!!
            val cause = stale.unresolved.single() as Cause.SourceUnavailable
            cause.document.assert().isEqualTo(DocumentKind.TENANT)
            (cause.error as SnapshotStaleException).tenantId.assert().isEqualTo("acme")
            decide(stale, scope("acme")).assert().isInstanceOf(Decision.Indeterminate::class.java)
            // Another tenant, as stale, is not configured to fail closed.
            views.peek(beta)!!.unresolved.assert().isEmpty()
            decide(views.peek(beta)!!, scope("beta")).assert().isInstanceOf(Decision.Permit::class.java)
        }
    }

    private fun awaitCompiled(views: DocumentSnapshotViews, key: ViewKey) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (views.load(key).join().tenant == null) {
            check(System.nanoTime() < deadline) { "The tenant document was not compiled again." }
            Thread.sleep(10)
        }
    }
}
