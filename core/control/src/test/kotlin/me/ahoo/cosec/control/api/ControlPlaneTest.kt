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

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.EngineAuthority
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.change.ChangeAuthority
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.control.store.StoredDocument
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.engine.flat.ViewVersion
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * RFC 0007 §10.1, §10.3 through the real engine: the control plane's reads are authorized like its writes, and
 * `simulate`, `explain` and `analyze` decide with the enforcing flow while showing only what the caller may read.
 */
class ControlPlaneTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)

    /** Changes whose stored record does not decode, as one of an app no document key can name (SCP-10). */
    val unreadable = HashSet<ChangeId>()

    /** Documents whose stored head does not decode, as one a newer version wrote during a rolling upgrade (RC1-39). */
    val undecodable = HashSet<SnapshotKey>()
    private val store: ControlStore = object : ControlStore by memory {
        override suspend fun change(id: ChangeId): Versioned<ChangeRequest>? = if (id in unreadable) {
            // Wrapped, as a store's cache may wrap what its codec threw.
            throw IllegalStateException("decoding", SnapshotFormatException("A change request is malformed."))
        } else {
            memory.change(id)
        }

        override suspend fun head(key: SnapshotKey): StoredDocument? = memory.head(key)?.let { stored ->
            if (key !in undecodable) return@let stored
            val document = stored.document
            val body = """{"schema":2,"revision":${document.revision},"publishedAt":"2026-10-27T00:00:00Z","next":1}"""
            StoredDocument(
                SnapshotDocument(document.schema, document.revision, document.publishedAt, body.encodeToByteArray()),
                SnapshotDigest(ByteArray(32) { 7 }),
            )
        }
    }
    private val revisions = Revisions(store, clock)
    val engine = EngineAuthority(revisions)

    /** How many changes were made. */
    val ids = AtomicInteger()
    private val options = ChangeOptions(shadow = IMMEDIATE_WINDOW)
    val changes = Changes(
        store,
        revisions,
        { ChangeId("c${ids.incrementAndGet()}") },
        engine.authorizer,
        clock,
        options
    )
    val simulator = Simulator()
    val plane = DefaultControlPlane(changes, revisions, ChangeAuthority(engine.authorizer, options), simulator)

    val plato = engine.operator(TenantId.PLATFORM, "plato")
    val alice = engine.operator(ACME, "alice", roles = listOf("admin", "ops@team-a"))
    val mallory = engine.operator(GLOBEX, "mallory", roles = listOf("admin"))

    init {
        runBlocking {
            val noDelete = PolicyDefinition(
                PolicyId("no-delete"),
                false,
                null,
                listOf(StatementSource("", Effect.DENY, listOf("order:delete"), listOf(ResourceSource("order/**")))),
            )
            bootstrap(
                SnapshotKey.Platform,
                listOf(noDelete),
                listOf(
                    control(
                        "plato",
                        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("plato")),
                        BuiltinPolicies.PLATFORM_ADMIN
                    ),
                    Binding(
                        BindingId("no-delete"),
                        SubjectRef.Anyone,
                        BindingScope(Match.Any, Match.Any),
                        PolicyId("no-delete")
                    ),
                ),
            )
            for (tenant in listOf(ACME, GLOBEX)) {
                bootstrap(
                    SnapshotKey.Tenant(tenant.value),
                    listOf(allow("orders", action = "order:*", resource = "order/**")),
                    listOf(
                        control("admins", SubjectRef.Role(tenant, RoleId("admin")), BuiltinPolicies.TENANT_ADMIN),
                        control("viewers", SubjectRef.Role(tenant, RoleId("viewer")), BuiltinPolicies.TENANT_VIEWER),
                        Binding(
                            BindingId("ops"),
                            SubjectRef.Role(tenant, RoleId("ops")),
                            BindingScope(Match.Exactly(ORDER), Match.Any),
                            PolicyId("orders")
                        ),
                    ),
                )
            }
        }
    }

    suspend fun bootstrap(key: SnapshotKey, policies: List<PolicyDefinition>, bindings: List<Binding>) {
        revisions.publish(
            key,
            revisions.head(key),
            StoreFixtures.candidate(key, policies, bindings),
            Publication("bootstrap", ProvenanceKind.BOOTSTRAP, plato.actor)
        )
    }

    private fun control(id: String, subject: SubjectRef, policy: PolicyId) =
        Binding(
            BindingId(id),
            subject,
            BindingScope(Match.Exactly(PipelineControlAuthorizer.CONTROL_APP), Match.Exactly(SpaceId.DEFAULT)),
            policy
        )

    private suspend fun refused(block: suspend () -> Unit): Int = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e.status
    }

    /** bob of [tenant] in team-a of the order app, as `ops` there. */
    private fun bob(action: String, tenant: TenantId = ACME) = SimulatedRequest(
        tenant = tenant,
        principal = "bob",
        roles = listOf("ops@team-a"),
        app = "order",
        space = "team-a",
        action = Action("order", action),
        resource = Resource("order", "1", emptyMap()),
        time = clock.now.toEpochMilli(),
    )

    @Test
    fun `reads are authorized, and an unreadable change is not there`() = runBlocking<Unit> {
        plane.read(alice, TENANT).bindings.map { it.id.value }.assert().contains("ops")
        refused { plane.read(alice, SnapshotKey.Platform) }.assert().isEqualTo(403)
        refused { plane.read(mallory, TENANT) }.assert().isEqualTo(403)
        plane.history(alice, TENANT).single().revision.assert().isEqualTo(1)
        refused { plane.read(alice, TENANT, 9) }.assert().isEqualTo(410)
        refused { plane.read(alice, SnapshotKey.Tenant("nobody")) }.assert().isEqualTo(403)

        val change = plane.propose(alice, TENANT, 1, listOf(Operation.PutPolicy(allow("more"))), "more")
        plane.get(alice, change.id).id.assert().isEqualTo(change.id)
        refused { plane.get(mallory, change.id) }.assert().isEqualTo(404)
        refused { plane.get(mallory, ChangeId("missing")) }.assert().isEqualTo(404)
        plane.startShadow(alice, change.id)
        changes.shadowed(change.id)
        plane.publish(alice, change.id)
        plane.history(alice, TENANT).map { it.revision }.assert().containsExactly(2L, 1L)
        plane.history(alice, TENANT, before = 2, limit = 1).map { it.revision }.assert().containsExactly(1L)
        plane.read(alice, TENANT, 1).policies.map { it.id.value }.assert().containsExactly("orders")
        val diff = plane.diff(alice, TENANT, 1, 2)
        diff.policies.added.assert().containsExactly("more")
        diff.bindings.assert().isEqualTo(EntryChanges(emptyList(), emptyList(), emptyList()))
        diff.sections.assert().isFalse()
        plane.propagation(alice, TENANT, 2).recent.assert().isEqualTo(0)
    }

    @Test
    fun `simulate decides with the enforcing flow and shows only what the caller may read`() = runBlocking<Unit> {
        val read = plane.simulate(alice, bob("read"))
        read.enforced.decision.assert().isEqualTo("Permit")
        read.enforced.by!!.policy.assert().isEqualTo("orders")
        read.enforced.by!!.via.assert().isEqualTo("ops")
        // The platform's DENY decides; alice may not read the platform, so the statement is not named.
        val delete = plane.simulate(alice, bob("delete"))
        delete.enforced.decision.assert().isEqualTo("Deny")
        delete.enforced.by.assert().isNull()
        delete.enforced.redacted.assert().isTrue()
        delete.enforcedTrace!!.redacted.assert().isGreaterThan(0)
        delete.enforcedTrace!!.entries.none { it.statement.document == "platform" }.assert().isTrue()
        plane.simulate(plato, bob("delete")).enforced.by!!.policy.assert().isEqualTo("no-delete")
        // The hard rule: another tenant's principal simulates nothing here.
        refused { plane.simulate(mallory, bob("read")) }.assert().isEqualTo(403)
        // A scope the principal is no member of is rejected, as the pipeline rejects it.
        plane.simulate(alice, bob("read").copy(roles = emptyList())).enforced.decision.assert().isEqualTo("Rejected")

        // A change's candidate, on the same request.
        val change = plane.propose(alice, TENANT, 1, listOf(Operation.RemoveBinding(BindingId("ops"))), "revoke")
        val simulated = plane.simulate(alice, bob("read"), change.id)
        simulated.candidate!!.decision.assert().isEqualTo("Deny")
        simulated.differs.assert().isTrue()
        val other = plane.propose(
            mallory,
            SnapshotKey.Tenant(GLOBEX.value),
            1,
            listOf(Operation.PutPolicy(allow("x"))),
            "theirs"
        )
        refused { plane.simulate(alice, bob("read"), other.id) }.assert().isEqualTo(404)
    }

    @Test
    fun `explain decides at the revisions an audit event recorded`() = runBlocking<Unit> {
        val change = plane.propose(alice, TENANT, 1, listOf(Operation.RemoveBinding(BindingId("ops"))), "revoke")
        plane.startShadow(alice, change.id)
        changes.shadowed(change.id)
        plane.publish(alice, change.id)
        plane.explain(alice, bob("read")).decision.decision.assert().isEqualTo("Deny")
        val then = plane.explain(alice, bob("read"), ViewVersion(1, null, 1))
        then.decision.decision.assert().isEqualTo("Permit")
        then.version.tenant.assert().isEqualTo(1L)
        refused { plane.explain(alice, bob("read"), ViewVersion(1, null, 7)) }.assert().isEqualTo(410)
        refused { plane.explain(mallory, bob("read")) }.assert().isEqualTo(403)
    }

    @Test
    fun `analyze lists what the caller may read, and counts the rest`() = runBlocking<Unit> {
        val query = AccessQuery(ACME, "order", "team-a", Action("order", "delete"), Resource("order", "1", emptyMap()))
        val forAlice = plane.analyze(alice, query)
        forAlice.allows.map { it.binding }.assert().containsExactly("ops")
        forAlice.allows.single().subject.assert().isEqualTo("role:acme/ops")
        forAlice.denies.assert().isEmpty()
        // The platform's document and the order app's: alice may read neither.
        forAlice.hidden.assert().isEqualTo(2)
        val forPlato = plane.analyze(plato, query)
        forPlato.denies.map { it.binding }.assert().containsExactly("no-delete")
        forPlato.hidden.assert().isEqualTo(0)
        refused { plane.analyze(mallory, query) }.assert().isEqualTo(403)
        plane.analyze(alice, query.copy(app = "billing")).allows.assert().isEmpty()
    }

    /** SCP-10, R1-6 of #1194's review: the control plane takes no app the data plane could never read. */
    @Test
    fun `analyze refuses an app that cannot be an app id`() = runBlocking<Unit> {
        val query = AccessQuery(ACME, "order", "team-a", Action("order", "delete"), Resource("order", "1", emptyMap()))
        for (app in listOf("a\nforged", "a\u0000b", "a\u007Fb", " ", "*", "a:b", "x".repeat(129))) {
            refused { plane.analyze(plato, query.copy(app = app)) }.assert().isEqualTo(400)
        }
        // A space inside, or a letter outside ASCII, is an app id.
        plane.analyze(plato, query.copy(app = "order app")).allows.assert().isEmpty()
    }

    /**
     * RC1-40, `CTL-23`: a simulated or explained request is authorized on the tenant whose document decides it. Without
     * a principal that is tenant `(0)`, as enforcement decides it, so a request that names another one is refused, after
     * the authorization on `(0)`.
     */
    @Test
    fun `a request without a principal is authorized and decided on tenant (0) only`() = runBlocking<Unit> {
        val anonymous = bob("read").copy(principal = null, roles = emptyList(), app = "", space = "")
        // Authorized on (0), whose document would decide it: alice, acme's administrator, simulates nothing there.
        refused { plane.simulate(alice, anonymous) }.assert().isEqualTo(403)
        refused { plane.explain(alice, anonymous) }.assert().isEqualTo(403)
        // plato may simulate on (0), but not under acme's name: that is no request enforcement would decide on acme.
        refused { plane.simulate(plato, anonymous) }.assert().isEqualTo(400)
        refused { plane.explain(plato, anonymous) }.assert().isEqualTo(400)
        val zero = anonymous.copy(tenant = TenantId.DEFAULT)
        plane.simulate(plato, zero).enforced.decision.assert().isEqualTo("Deny")
        plane.explain(plato, zero).decision.decision.assert().isEqualTo("Deny")
        refused { plane.simulate(alice, zero) }.assert().isEqualTo(403)
        // With a principal, the request's own tenant decides, as before.
        plane.simulate(alice, bob("read")).enforced.decision.assert().isEqualTo("Permit")
    }

    /** `PIP-12`, `CTL-3`: a request whose header a document of its view reads, sent with different values, is a 400. */
    @Test
    fun `a header the view reads sent with different values is refused as enforcement refuses it`() = runBlocking<Unit> {
        val initech = SnapshotKey.Tenant("initech")
        bootstrap(
            initech,
            listOf(
                allow("debug", action = "order:read", condition = Condition.Eq("http.header.x-debug", Literal.Str("1")))
            ),
            listOf(
                Binding(BindingId("debug"), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), PolicyId("debug"))
            ),
        )
        val request = bob("read", TenantId("initech")).copy(roles = emptyList(), app = "", space = "")
        plane.simulate(plato, request.copy(headers = mapOf("X-Debug" to listOf("1")))).enforced.decision
            .assert().isEqualTo("Permit")
        // Identical repeats are one value.
        plane.simulate(plato, request.copy(headers = mapOf("X-Debug" to listOf("1", "1")))).enforced.decision
            .assert().isEqualTo("Permit")
        val twice = request.copy(headers = mapOf("X-Debug" to listOf("1", "0")))
        refused { plane.simulate(plato, twice) }.assert().isEqualTo(400)
        refused { plane.explain(plato, twice) }.assert().isEqualTo(400)
        // A header no document reads is not checked.
        plane.simulate(plato, request.copy(headers = mapOf("X-Debug" to listOf("1"), "X-Other" to listOf("a", "b"))))
            .enforced.decision.assert().isEqualTo("Permit")
    }

    /**
     * `PIP-18`, `CTL-3`: an `http` action is read as enforcement reads a request's method: HEAD as GET, which a DENY on
     * `http:GET` denies, and a verb with a lower-case letter, which no request is decided as, refused.
     */
    @Test
    fun `an http action is read as enforcement reads a method`() = runBlocking<Unit> {
        val initech = SnapshotKey.Tenant("initech")
        val noRead = PolicyDefinition(
            PolicyId("no-read"),
            attachable = false,
            condition = null,
            statements = listOf(
                StatementSource("", Effect.DENY, listOf("http:GET"), listOf(ResourceSource("http:path:/salaries/**")))
            ),
        )
        bootstrap(
            initech,
            listOf(allow("open", action = "http:*", resource = "http:path:/salaries/**"), noRead),
            listOf("open", "no-read").map {
                Binding(BindingId(it), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), PolicyId(it))
            },
        )
        val request = bob("read", TenantId("initech"))
            .copy(roles = emptyList(), app = "", space = "", resource = Resource("http", "/salaries/7", emptyMap()))
        fun with(verb: String) = request.copy(action = Action("http", verb))
        plane.simulate(plato, with("POST")).enforced.decision.assert().isEqualTo("Permit")
        plane.simulate(plato, with("HEAD")).enforced.decision.assert().isNotEqualTo("Permit")
        plane.explain(plato, with("HEAD")).decision.decision.assert().isNotEqualTo("Permit")
        refused { plane.simulate(plato, with("delete")) }.assert().isEqualTo(400)
        refused { plane.explain(plato, with("delete")) }.assert().isEqualTo(400)
        val query = AccessQuery(TenantId("initech"), null, null, Action("http", "delete"), request.resource)
        refused { plane.analyze(plato, query) }.assert().isEqualTo(400)
    }

    /** RC1-41, review L5 of #1200: a resource no request path could be is not decided, nor analyzed. */
    @Test
    fun `a resource is held to a request path's bounds`() = runBlocking<Unit> {
        val long = "x".repeat(257)
        refused { plane.simulate(alice, bob("read").copy(resource = Resource("http", "/$long", emptyMap()))) }
            .assert().isEqualTo(400)
        refused { plane.explain(alice, bob("read").copy(resource = Resource("order", long, emptyMap()))) }
            .assert().isEqualTo(400)
        val query = AccessQuery(
            ACME,
            "order",
            "team-a",
            Action("order", "read"),
            Resource("order", "1/$long", emptyMap())
        )
        refused { plane.analyze(alice, query) }.assert().isEqualTo(400)
        val bounded = "x".repeat(256)
        plane.simulate(alice, bob("read").copy(resource = Resource("order", bounded, emptyMap()))).enforced.decision
            .assert().isEqualTo("Permit")
        plane.analyze(alice, query.copy(resource = Resource("order", bounded, emptyMap()))).allows
            .assert().hasSize(1)
    }

    /** `raw`, matched as written: only a normalized path, without its parameter, matches it (review L7 of #1240). */
    private fun rawPolicy() = PolicyDefinition(
        PolicyId("raw"),
        false,
        null,
        listOf(
            StatementSource(
                "",
                Effect.ALLOW,
                listOf("http:GET"),
                listOf(ResourceSource("http:path:/raw/{id}", decodeAndParseSegments = false)),
            ),
        ),
    )

    /**
     * RC1-41, coordinator 2026-11-07 (`CTL-3`): an `http` resource goes through the path normalization enforcement
     * applies. A path enforcement answers 400 for is refused; one it normalizes is decided normalized, its path
     * parameters removed, as a request to it would be.
     */
    @Test
    fun `an http resource is normalized as enforcement normalizes a path`() = runBlocking<Unit> {
        val initech = SnapshotKey.Tenant("initech")
        bootstrap(
            initech,
            listOf(
                allow("admin", action = "http:GET", resource = "http:path:/admin/{id}"),
                rawPolicy(),
            ),
            listOf("admin", "raw").map {
                Binding(BindingId(it), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), PolicyId(it))
            },
        )
        val request = bob("read", TenantId("initech"))
            .copy(roles = emptyList(), app = "", space = "", action = Action("http", "GET"))
        fun at(path: String) = request.copy(resource = Resource("http", path, emptyMap()))
        val refusedPaths = listOf(
            "/public/../admin/1",
            "/public/%2e%2e/admin/1",
            "/admin/..;x/1",
            "/admin\\..\\1",
            "/admin/%zz"
        )
        for (path in refusedPaths) {
            refused { plane.simulate(plato, at(path)) }.assert().describedAs(path).isEqualTo(400)
            refused { plane.explain(plato, at(path)) }.assert().describedAs(path).isEqualTo(400)
            val query = AccessQuery(
                TenantId("initech"),
                null,
                null,
                Action("http", "GET"),
                Resource("http", path, emptyMap())
            )
            refused { plane.analyze(plato, query) }.assert().describedAs(path).isEqualTo(400)
        }
        plane.simulate(plato, at("/admin/1")).enforced.decision.assert().isEqualTo("Permit")
        // As a request to /admin;v=2/1 is decided on /admin/1.
        plane.simulate(plato, at("/admin;v=2/1")).enforced.decision.assert().isEqualTo("Permit")
        // Review L7 of #1240: decided on the normalized path, not the one given, which a pattern matched as written tells.
        plane.simulate(plato, at("/raw;v=2/1")).enforced.by!!.policy.assert().isEqualTo("raw")
        plane.explain(plato, at("/raw;v=2/1")).decision.decision.assert().isEqualTo("Permit")
        plane.analyze(
            plato,
            AccessQuery(
                TenantId("initech"),
                null,
                null,
                Action("http", "GET"),
                Resource("http", "/admin;v=2/1", emptyMap())
            ),
        ).allows.map { it.binding }.assert().containsExactly("admin")
    }

    /**
     * RC1-41: `simulate`, `explain` and `analyze` decode and compile a stored document once for its content, in one
     * cache; a candidate, which is no stored content, is compiled once per call, and a head that moves is compiled again.
     */
    @Test
    fun `stored documents are compiled once for their content`() = runBlocking<Unit> {
        plane.simulate(alice, bob("read"))
        val compiled = simulator.compilations.get()
        // The platform's and acme's documents; the order app has none.
        compiled.assert().isEqualTo(2L)
        repeat(3) { plane.simulate(alice, bob("read")) }
        plane.explain(alice, bob("read"))
        plane.explain(alice, bob("read"), ViewVersion(1, null, 1))
        plane.analyze(
            alice,
            AccessQuery(ACME, "order", "team-a", Action("order", "read"), Resource("order", "1", emptyMap()))
        )
        simulator.compilations.get().assert().isEqualTo(compiled)
        val held = simulator.cache.size

        val change = plane.propose(alice, TENANT, 1, listOf(Operation.RemoveBinding(BindingId("ops"))), "revoke")
        plane.simulate(alice, bob("read"), change.id).candidate!!.decision.assert().isEqualTo("Deny")
        // The candidate alone, once.
        simulator.compilations.get().assert().isEqualTo(compiled + 1)
        plane.simulate(alice, bob("read"), change.id)
        simulator.compilations.get().assert().isEqualTo(compiled + 2)
        simulator.cache.size.assert().isEqualTo(held)

        plane.startShadow(alice, change.id)
        changes.shadowed(change.id)
        plane.publish(alice, change.id)
        // acme's new head is new content; the platform's is not.
        plane.simulate(alice, bob("read")).enforced.decision.assert().isEqualTo("Deny")
        simulator.compilations.get().assert().isEqualTo(compiled + 3)
        plane.simulate(alice, bob("read"))
        simulator.compilations.get().assert().isEqualTo(compiled + 3)
    }

    /** A policy reading an attribute only the platform declares: it stops compiling once the platform drops it. */
    private fun geo(id: String = "geo", value: String = "NZ") =
        allow(id, action = "order:none", condition = Condition.Eq("ext.geo.country", Literal.Str(value)))

    private suspend fun platformDeclares(vararg attributes: Attribute) {
        val head = revisions.head(SnapshotKey.Platform)!!
        val document = PolicyDocument(
            head.document.kind,
            head.document.owner,
            head.revision,
            head.document.publishedAt,
            head.document.policies,
            head.document.bindings,
            attributes = attributes.toList(),
        )
        revisions.publish(
            SnapshotKey.Platform,
            head,
            document,
            Publication("attrs", ProvenanceKind.BOOTSTRAP, plato.actor)
        )
            .assert().isInstanceOf(PublishOutcome.Published::class.java)
    }

    /** Acme's document with [geo] added, and a change on top of it; the platform then stops declaring the attribute. */
    private suspend fun brokenByThePlatform(vararg operations: Operation): ChangeRequest {
        platformDeclares(Attribute.external("ext.geo.country", AttributeType.STRING))
        val added = plane.propose(alice, TENANT, 1, listOf(Operation.PutPolicy(geo())), "geo")
        added.state.assert().isEqualTo(ChangeState.CHECKED)
        plane.skipShadow(plato, added.id, "test")
        plane.publish(plato, added.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        val change = plane.propose(alice, TENANT, 2, operations.toList(), "on top")
        change.state.assert().isEqualTo(ChangeState.CHECKED)
        platformDeclares()
        return change
    }

    @Test
    fun `the same compile failure on both sides does not differ - #1253`() = runBlocking<Unit> {
        val change = brokenByThePlatform(Operation.PutPolicy(geo()))

        val simulated = plane.simulate(plato, bob("read"), change.id)

        simulated.enforced.decision.assert().isEqualTo("Indeterminate")
        simulated.candidate.assert().isEqualTo(simulated.enforced)
        simulated.differs.assert().isFalse()
    }

    @Test
    fun `a candidate that fails another way differs from the enforced failure - #1253`() = runBlocking<Unit> {
        val change = brokenByThePlatform(Operation.PutPolicy(geo("geo-too")))

        val simulated = plane.simulate(plato, bob("read"), change.id)

        simulated.enforced.decision.assert().isEqualTo("Indeterminate")
        simulated.candidate!!.decision.assert().isEqualTo("Indeterminate")
        simulated.differs.assert().isTrue()
    }

    @Test
    fun `a candidate that repairs the failure differs - #1253`() = runBlocking<Unit> {
        val change = brokenByThePlatform(Operation.RemovePolicy(PolicyId("geo")))

        val simulated = plane.simulate(plato, bob("read"), change.id)

        simulated.enforced.decision.assert().isEqualTo("Indeterminate")
        simulated.candidate!!.decision.assert().isEqualTo("Permit")
        simulated.differs.assert().isTrue()
    }

    companion object {
        val ACME = TenantId("acme")
        val GLOBEX = TenantId("globex")
        val TENANT: SnapshotKey = SnapshotKey.Tenant("acme")
        val ORDER = AppId("order")
    }
}
