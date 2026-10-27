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

package me.ahoo.cosec.snapshot.document

import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.Scope
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/** Documents are written inline, one JSON line each. */
@Suppress("MaxLineLength")
class PolicyDocumentCompilerTest {
    private val compiler = PolicyDocumentCompiler()

    private fun stored(body: String, schema: Int = 2) = SnapshotDocument(
        schema,
        1,
        Instant.parse("2026-10-26T00:00:00Z"),
        """{"schema":$schema,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"$body}""".encodeToByteArray(),
    )

    private fun errors(key: SnapshotKey, body: String): List<String> =
        try {
            compiler.compile(key, stored(body))
            throw AssertionError("compiled $body")
        } catch (error: DocumentCompileException) {
            error.errors.map { "${it.path}: ${it.message}" }
        }

    private val platform = """
        ,"policies":[{"id":"deny-admin","statements":[{"name":"admin","effect":"deny","action":"http:*","resource":"http:path:/admin/**"}]},
                     {"id":"shared","statements":[{"name":"read","effect":"allow","action":"order:read","resource":"order/{id}"}]}],
         "bindings":[{"id":"g","subject":"*","app":"*","space":"*","policy":"deny-admin"}]
    """.trimIndent()

    private val app = """
        ,"catalog":{"groups":[{"name":"order","permissions":[{"id":"ship"}]}]},
         "policies":[{"id":"perm:order/ship","condition":{"eq":["subject.authenticated",true]},
                      "statements":[{"name":"ship","effect":"allow","action":"order:ship","resource":"order/{id}"}]}],
         "bindings":[{"id":"r1","subject":{"role":"shipper"},"app":"order","space":"*","policy":"perm:order/ship"},
                     {"id":"r2","subject":{"role":"viewer"},"app":"order","space":"*","policy":"shared"}]
    """.trimIndent()

    private val tenant = """
        ,"policies":[{"id":"no-cancel","statements":[{"name":"cancel","effect":"deny","action":"order:cancel"}]}],
         "bindings":[{"id":"t","subject":{"role":"shipper"},"app":"*","space":"*","policy":"no-cancel"}]
    """.trimIndent()

    private fun decide(action: Action, resource: Resource, roles: List<String>): Decision {
        val memberships = Memberships(
            Memberships.Source.CLAIMS,
            mapOf(SpaceId.DEFAULT to roles.mapTo(LinkedHashSet(), ::RoleId)),
            emptyList(),
        )
        val resolution = ScopeResolver.resolve(
            ScopeSubject(TenantId("acme"), true, memberships),
            DeclaredScope("order", ""),
            HostScope(null)
        )
        val authz = AuthzRequest(
            Subject("alice", "acme", true, emptySet(), emptySet(), emptyMap(), false),
            Scope.declared("acme", "order", ""),
            action,
            resource,
            Environment("10.0.0.1", "r", "d", blocked = false),
        )
        val view = SnapshotView(
            compiler.compile(SnapshotKey.Platform, stored(platform)),
            compiler.compile(SnapshotKey.App("order"), stored(app)),
            compiler.compile(SnapshotKey.Tenant("acme"), stored(tenant)),
        )
        return CoSecFlatEngine.evaluate(
            FlatRequest(authz, (resolution as ScopeResolution.Resolved).scope, memberships, 0),
            view
        ).decision
    }

    @Test
    fun `platform, app and tenant documents decide together`() {
        val ship = decide(Action("order", "ship"), Resource("order", "1", emptyMap()), listOf("shipper"))
        ship.assert().isEqualTo(
            Decision.Permit(
                StatementRef.Policy(
                    PolicyId("perm:order/ship"),
                    0,
                    "ship",
                    BindingRef.Bound(me.ahoo.cosec.model.BindingId("r1"), DocumentKind.APP)
                )
            ),
        )
        decide(Action("order", "read"), Resource("order", "1", emptyMap()), listOf("viewer")).assert()
            .isInstanceOf(Decision.Permit::class.java)
        decide(Action("order", "cancel"), Resource("order", "1", emptyMap()), listOf("shipper")).assert()
            .isEqualTo(
                Decision.Deny(
                    StatementRef.Policy(
                        PolicyId("no-cancel"),
                        0,
                        "cancel",
                        BindingRef.Bound(me.ahoo.cosec.model.BindingId("t"), DocumentKind.TENANT)
                    )
                ),
            )
        decide(Action("http", "GET"), Resource("http", "/admin/x", emptyMap()), emptyList()).assert()
            .isInstanceOf(Decision.Deny::class.java)
        decide(
            Action("order", "read"),
            Resource("order", "1", emptyMap()),
            emptyList()
        ).assert().isEqualTo(Decision.Deny(null))
    }

    /** RFC 0007 §7.6: the platform's default boundary holds every tenant that names none, its own one instead. */
    @Test
    fun `a platform boundary holds a tenant's permits`() {
        val platform = compiler.compile(
            SnapshotKey.Platform,
            stored(
                ""","boundaries":{"default":"orders-only"},
                   "policies":[{"id":"all","statements":[{"effect":"allow","action":"*"}]},
                               {"id":"orders-only","boundary":true,"statements":[{"effect":"allow","action":"order:*"}]},
                               {"id":"anything","boundary":true,"statements":[{"effect":"allow","action":"*"}]}],
                   "bindings":[{"id":"g","subject":"*","app":"*","space":"*","policy":"all"}]"""
            ),
        )
        fun decide(tenantBody: String, action: Action): Decision {
            val memberships = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())
            val scope = (
                ScopeResolver.resolve(
                    ScopeSubject(TenantId("acme"), true, memberships),
                    DeclaredScope("", ""),
                    HostScope(null)
                ) as ScopeResolution.Resolved
                ).scope
            val authz = AuthzRequest(
                Subject("alice", "acme", true, emptySet(), emptySet(), emptyMap(), false),
                Scope.declared("acme", "", ""),
                action,
                Resource("order", "1", emptyMap()),
                Environment("10.0.0.1", "r", "d", blocked = false),
            )
            val view = SnapshotView(platform, null, compiler.compile(SnapshotKey.Tenant("acme"), stored(tenantBody)))
            return CoSecFlatEngine.evaluate(FlatRequest(authz, scope, memberships, 0), view).decision
        }
        decide("", Action("order", "read")).assert().isInstanceOf(Decision.Permit::class.java)
        decide("", Action("user", "read")).assert()
            .isEqualTo(Decision.Deny(StatementRef.Boundary(PolicyId("orders-only"), TenantId("acme"))))
        decide(""","boundary":"anything"""", Action("user", "read")).assert().isInstanceOf(Decision.Permit::class.java)
    }

    @Test
    fun `boundaries live in the platform, only allow, and are never bound`() {
        errors(SnapshotKey.Tenant("acme"), ""","policies":[{"id":"b","boundary":true,"statements":[]}]""")
            .assert().containsExactly("$.policies[0].boundary: only the platform document holds boundaries")
        errors(
            SnapshotKey.Platform,
            ""","policies":[{"id":"b","boundary":true,"attachable":true,"statements":[{"effect":"deny","action":"*"}]}]"""
        )
            .assert().containsExactly(
                "$.policies[0].attachable: a boundary cannot be attached",
                "$.policies[0].statements[0].effect: a boundary only allows",
            )
        val many = (1..65).joinToString(",") { """{"effect":"allow","action":"*"}""" }
        errors(SnapshotKey.Platform, ""","policies":[{"id":"b","boundary":true,"statements":[$many]}]""")
            .assert().containsExactly("$.policies[0].statements: 65 statements, more than 64")
        errors(
            SnapshotKey.Platform,
            ""","policies":[{"id":"b","boundary":true,"statements":[]}],"bindings":[{"id":"g","subject":"*","app":"*","space":"*","policy":"b"}]"""
        )
            .assert().containsExactly("$.bindings[0].policy: [b] is a boundary; boundaries are not bound")
        errors(SnapshotKey.Platform, ""","boundaries":{"default":"p"},"policies":[{"id":"p","statements":[]}]""")
            .assert().containsExactly("$.boundaries.default: [p] is not a boundary of this document")
        errors(SnapshotKey.Tenant("(platform)"), ""","boundary":"b"""")
            .assert().containsExactly("$.boundary: the platform tenant has no boundary")
        assertThrownBy<SnapshotFormatException> {
            compiler.compile(
                SnapshotKey.App("order"),
                stored(""","boundary":"b"""")
            )
        }
        assertThrownBy<SnapshotFormatException> {
            compiler.compile(
                SnapshotKey.Tenant("acme"),
                stored(""","boundaries":{"default":"b"}""")
            )
        }
        assertThrownBy<SnapshotFormatException> {
            compiler.compile(
                SnapshotKey.Platform,
                stored(""","boundary":"b"""")
            )
        }
    }

    /** RFC 0007 §3.1 N3: documents compile with the platform's limits and attributes, each declared once. */
    @Test
    fun `the platform document sets what every document compiles against`() {
        errors(
            SnapshotKey.Platform,
            ""","attributes":[{"path":"ext.geo.region","type":"string"},{"path":"ext.geo.region","type":"ip"}]"""
        )
            .assert().containsExactly("$.attributes[1].path: [ext.geo.region] is declared twice")
        val platform = PolicyDocumentCodec.decode(
            DocumentKind.PLATFORM,
            "",
            stored(
                ""","limits":{"maxInValues":2},"attributes":[{"path":"ext.geo.region","type":"string"}]"""
            ).bodyBytes(),
        )
        val tenant = { condition: String ->
            PolicyDocumentCodec.decode(
                DocumentKind.TENANT,
                "acme",
                stored(
                    ""","policies":[{"id":"p","statements":[{"effect":"allow","action":"*","condition":$condition}]}]"""
                ).bodyBytes(),
            )
        }
        val reading = tenant("""{"eq":["ext.geo.region","eu"]}""")
        PolicyDocumentCompiler.forPlatform(platform).compile(reading).owner.assert().isEqualTo("acme")
        assertThrownBy<DocumentCompileException> { PolicyDocumentCompiler.forPlatform(null).compile(reading) }
        val wide = tenant("""{"in":["scope.app",["a","b","c"]]}""")
        PolicyDocumentCompiler.forPlatform(null).compile(wide).owner.assert().isEqualTo("acme")
        assertThrownBy<DocumentCompileException> { PolicyDocumentCompiler.forPlatform(platform).compile(wide) }
    }

    /** DOC-3: a document that is not this version's format fails to decode; RFC 0003's keys are not read either. */
    @Test
    fun `only this format and these keys compile`() {
        assertThrownBy<SnapshotFormatException> { compiler.compile(SnapshotKey.Platform, stored(platform, schema = 1)) }
            .hasMessageContaining("schema 1")
        assertThrownBy<SnapshotFormatException> { compiler.compile(SnapshotKey.Policies, stored(platform)) }
        assertThrownBy<SnapshotFormatException> { compiler.compile(SnapshotKey.Platform, stored(app)) }
            .hasMessageContaining("unknown field catalog")
    }

    @Test
    fun `statements that do not compile are errors with their paths`() {
        errors(
            SnapshotKey.Platform,
            ""","policies":[{"id":"p","statements":[{"effect":"deny","action":"order","resource":"order"},
                                                    {"effect":"allow","action":"*","condition":{"eq":["nowhere","x"]}}]}]""",
        ).assert().containsExactly(
            "$.policies[0].statements[0].action[0]: Invalid pattern [order] at 0: an action is service:verb, service:* or *",
            "$.policies[0].statements[0].resource[0]: a DENY on the bare type [order] covers only the order with an empty id; " +
                "write [order/**] for every order, and add an eq on resource.id with the empty string for the empty id alone",
            "$.policies[0].statements[1].condition.eq[0]: unknown attribute `nowhere`",
        )
    }

    /** TNT-2: permission policies belong to their app's document; nothing else defines or binds them. */
    @Test
    fun `permission policies live in their app's document`() {
        errors(
            SnapshotKey.Tenant("acme"),
            ""","policies":[{"id":"perm:order/ship","statements":[]}]"""
        ).single().assert()
            .startsWith("$.policies[0].id: [perm:] names an app's permission policy")
        errors(
            SnapshotKey.Tenant("acme"),
            ""","bindings":[{"id":"b","subject":"*","app":"*","space":"*","policy":"perm:order/ship"}]"""
        )
            .single().assert().startsWith(
                "$.bindings[0].policy: [perm:order/ship] is an app's permission policy: a tenant binds it only scoped to that app"
            )
        // RFC 0007 §9.3 (N1): a tenant grants an app's permission scoped to that app, and to no other.
        compiler.compile(
            SnapshotKey.Tenant("acme"),
            stored(
                ""","bindings":[{"id":"b","subject":{"role":"editor"},"app":"order","space":"*","policy":"perm:order/ship"}]"""
            ),
        )
        errors(
            SnapshotKey.Tenant("acme"),
            ""","bindings":[{"id":"b","subject":{"role":"editor"},"app":"billing","space":"*","policy":"perm:order/ship"}]"""
        ).single().assert().contains("only scoped to that app")
        errors(
            SnapshotKey.Platform,
            ""","bindings":[{"id":"b","subject":"*","app":"order","space":"*","policy":"perm:order/ship"}]"""
        ).single().assert().contains("another app's permission policy")
        errors(SnapshotKey.Platform, ""","policies":[{"id":"perm:order/ship","statements":[]}]""").single().assert()
            .startsWith("$.policies[0].id")
        errors(SnapshotKey.App("order"), ""","policies":[{"id":"ship","statements":[]}]""").single().assert()
            .isEqualTo("$.policies[0].id: a policy of app order is [perm:order/<permission>], not [ship]")
        errors(SnapshotKey.App("order"), ""","policies":[{"id":"perm:order/ship","attachable":true,"statements":[]}]""")
            .single().assert().isEqualTo("$.policies[0].attachable: a permission policy cannot be attached")
        errors(
            SnapshotKey.App("order"),
            ""","bindings":[{"id":"b","subject":{"role":"r"},"app":"order","space":"*","policy":"perm:billing/pay"}]"""
        )
            .single().assert().contains("another app's permission policy")
        errors(
            SnapshotKey.App("order"),
            ""","bindings":[{"id":"b","subject":{"role":"r"},"app":"order","space":"*","policy":"perm:order/gone"}]"""
        )
            .single().assert().isEqualTo("$.bindings[0].policy: [perm:order/gone] is not defined in this document")
    }

    @Test
    fun `a document binds a bounded number of principals`() {
        val limited = PolicyDocumentCompiler(principalBindingLimit = 1)
        val bindings = (1..2).joinToString(",") {
            """{"id":"b$it","subject":{"principal":"p$it"},"app":"*","space":"*","policy":"p"}"""
        }
        val body = ""","policies":[{"id":"p","statements":[]}],"bindings":[$bindings]"""
        try {
            limited.compile(SnapshotKey.Tenant("acme"), stored(body))
            throw AssertionError("compiled")
        } catch (error: DocumentCompileException) {
            error.errors.single().message.assert().startsWith("2 principal bindings, more than 1")
            error.document.assert().isEqualTo("tenant acme")
        }
        PolicyDocumentCompiler(
            principalBindingLimit = 2
        ).compile(SnapshotKey.Tenant("acme"), stored(body)).owner.assert().isEqualTo("acme")
        assertThrownBy<IllegalArgumentException> { PolicyDocumentCompiler(principalBindingLimit = -1) }
    }

    /** What the engine checks (BND-3, BND-8, BRK-2, BRK-4) is a compile error of the document too. */
    @Test
    fun `binding shapes the engine refuses do not compile`() {
        errors(
            SnapshotKey.Tenant("acme"),
            ""","bindings":[{"id":"root","subject":{"principal":"cosec"},"app":"*","space":"*","policy":"cosec:root","breakGlass":true,"expiresAt":"2027-01-01T00:00:00Z"}]""",
        ).single().assert().contains("Break-glass binding root in the TENANT document")
        errors(
            SnapshotKey.Platform,
            ""","bindings":[{"id":"root","subject":{"principal":"alice","tenant":"acme"},"app":"*","space":"*","policy":"cosec:root","breakGlass":true,"expiresAt":"2027-01-01T00:00:00Z"}]""",
        ).single().assert().startsWith("$: ")
        errors(SnapshotKey.Platform, ""","bindings":[{"id":"b","subject":"*","app":"*","space":"*","policy":"gone"}]""")
            .single().assert().contains("which it does not define")
    }

    /** DOC-1: a publisher checks references against the platform it will be read with. */
    @Test
    fun `references to the platform are checked against a given platform`() {
        val platformDocument = PolicyDocumentCodec.decode(DocumentKind.PLATFORM, "", stored(platform).bodyBytes())
        val appDocument = PolicyDocumentCodec.decode(DocumentKind.APP, "order", stored(app).bodyBytes())
        compiler.checkReferences(appDocument, platformDocument)
        compiler.checkReferences(platformDocument, platformDocument)
        val dangling = PolicyDocumentCodec.decode(
            DocumentKind.TENANT,
            "acme",
            stored(
                ""","bindings":[{"id":"b","subject":"*","app":"*","space":"*","policy":"gone"},{"id":"r","subject":{"principal":"cosec","tenant":"acme"},"app":"*","space":"*","policy":"cosec:root"}]"""
            ).bodyBytes(),
        )
        try {
            compiler.checkReferences(dangling, platformDocument)
            throw AssertionError("checked")
        } catch (error: DocumentCompileException) {
            error.errors.map { it.path }.assert().containsExactly("$.bindings[0].policy")
        }
        // Without the check, the data plane compiles it and a request reaching the binding fails closed.
        compiler.compile(
            dangling.let {
                PolicyDocument(it.kind, it.owner, it.revision, it.publishedAt, it.policies, it.bindings.take(1))
            }
        )
            .owner.assert().isEqualTo("acme")
    }
}
