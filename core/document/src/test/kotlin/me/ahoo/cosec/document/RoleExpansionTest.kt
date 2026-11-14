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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.runtime.document

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/** The rules of RFC 0012 §4–§6, with documents written inline, one JSON line each. */
@Suppress("MaxLineLength")
class RoleExpansionTest {
    private val compiler = PolicyDocumentCompiler()

    private fun document(kind: DocumentKind, owner: String, body: String): PolicyDocument = PolicyDocumentCodec.decode(
        kind,
        owner,
        """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"$body}""".encodeToByteArray(),
    )

    private fun app(body: String) = document(DocumentKind.APP, "wms", body)

    private fun tenant(body: String) = document(DocumentKind.TENANT, "acme", body)

    private fun errors(document: PolicyDocument): List<String> = try {
        compiler.compile(document)
        emptyList()
    } catch (error: DocumentCompileException) {
        error.errors.map { "${it.path}: ${it.message}" }
    }

    private val catalog = ""","catalog":{"groups":[
        {"name":"ops","permissions":[{"id":"read","action":"wms:read"},{"id":"label"}]},
        {"name":"in","spaced":true,"permissions":[{"id":"ship","action":["wms:ship","wms:load"],"resource":"inbound/{id}"}]}]}"""

    @Test
    fun `a document with neither roles nor actions is its own effective form`() {
        val plain = app(""","catalog":{"groups":[{"name":"g","permissions":[{"id":"label"}]}]}""")
        RoleExpansion.effective(plain).assert().isSameAs(plain)
    }

    @Test
    fun `a permission with an action is a policy, restricted to the default space unless spaced`() {
        val effective = RoleExpansion.effective(app(catalog))
        effective.policies.map { it.id.value }.assert().containsExactly("perm:wms/read", "perm:wms/ship")
        effective.catalog!!.groups.flatMap {
            it.permissions
        }.all { it.actions == null && it.resources.isEmpty() }.assert().isTrue()
        val read = effective.policies[0]
        read.attachable.assert().isFalse()
        (read.condition as Condition.Eq).let {
            it.attribute.assert().isEqualTo("scope.space")
            it.value.assert().isEqualTo(Literal.Str(""))
        }
        val ship = effective.policies[1]
        ship.condition.assert().isNull()
        ship.statements.single().actions.assert().containsExactly("wms:ship", "wms:load")
        ship.statements.single().resources.single().pattern.assert().isEqualTo("inbound/{id}")
    }

    @Test
    fun `a system role derives bindings of any tenant after the hand-written ones`() {
        val effective = RoleExpansion.effective(
            app(
                catalog + ""","bindings":[{"id":"own","subject":{"role":"x"},"app":"wms","space":"*","policy":"perm:wms/read"}],"roles":[{"id":"clerk","permissions":["ship","read"]},{"id":"all","permissions":"*"},{"id":"none","permissions":[]}]"""
            )
        )
        effective.roles.assert().isEmpty()
        effective.bindings.map { it.id.value }.assert().containsExactly(
            "own",
            "role:clerk@wms:ship",
            "role:clerk@wms:read",
            "role:all@wms:read",
            "role:all@wms:ship",
        )
        val derived = effective.bindings[1]
        (derived.subject as SubjectRef.Role).tenant.assert().isNull()
        derived.scope.space.assert().isEqualTo(Match.Any)
        derived.policy.value.assert().isEqualTo("perm:wms/ship")
    }

    @Test
    fun `a custom role binds in its tenant, its app and its space`() {
        val effective = RoleExpansion.effective(
            tenant(
                ""","roles":[{"id":"keeper","app":"wms","space":"wh-01","permissions":["ship"]},{"id":"any","app":"pos","permissions":["ship"]}]"""
            )
        )
        val (keeper, any) = effective.bindings
        keeper.id.value.assert().isEqualTo("role:keeper@wms:ship")
        (keeper.subject as SubjectRef.Role).tenant.assert().isEqualTo(TenantId("acme"))
        keeper.scope.app.assert().isEqualTo(Match.Exactly(me.ahoo.cosec.model.AppId("wms")))
        keeper.scope.space.assert().isEqualTo(Match.Exactly(me.ahoo.cosec.model.SpaceId("wh-01")))
        any.scope.space.assert().isEqualTo(Match.Any)
        any.policy.value.assert().isEqualTo("perm:pos/ship")
    }

    @Test
    fun `a stored document keeps its short form through the codec`() {
        val stored = app(catalog + ""","roles":[{"id":"a","name":"A","description":"d","permissions":"*"}]""")
        val again = PolicyDocumentCodec.decode(DocumentKind.APP, "wms", PolicyDocumentCodec.encode(stored))
        again.roles.single().permissions.assert().isEqualTo(RolePermissions.All)
        again.roles.single().name.assert().isEqualTo("A")
        again.catalog!!.groups[1].permissions.single().actions.assert().containsExactly("wms:ship", "wms:load")
        PolicyDocumentCodec.encode(
            again
        ).decodeToString().assert().isEqualTo(PolicyDocumentCodec.encode(stored).decodeToString())
        val custom = tenant(""","roles":[{"id":"a","app":"wms","space":"","permissions":["x"]}]""")
        PolicyDocumentCodec.decode(
            DocumentKind.TENANT,
            "acme",
            PolicyDocumentCodec.encode(custom)
        ).roles.single().space!!.value.assert().isEqualTo("")
    }

    @Test
    fun `what each kind of document may write`() {
        fun decodes(block: () -> PolicyDocument) = assertThrownBy<SnapshotFormatException> { block() }
        decodes {
            tenant(
                ""","roles":[{"id":"a","app":"wms","permissions":"*"}]"""
            )
        }.hasMessageContaining("only for a system role")
        decodes { tenant(""","roles":[{"id":"a","permissions":[]}]""") }.hasMessageContaining("[app] is required")
        decodes { tenant(""","roles":[{"id":"a","app":"*","permissions":[]}]""") }.hasMessageContaining("role [a]")
        decodes {
            tenant(
                ""","roles":[{"id":"a","app":"wms","space":"*","permissions":[]}]"""
            )
        }.hasMessageContaining("role [a]")
        decodes { app(""","roles":[{"id":"a","space":"s","permissions":[]}]""") }.hasMessageContaining("unknown field")
        decodes { app(""","roles":[{"id":"a","permissions":"all"}]""") }.hasMessageContaining("permissions is a list")
        decodes { document(DocumentKind.PLATFORM, "", ""","roles":[]""") }.hasMessageContaining("unknown field")
    }

    @Test
    fun `an app's role of an undefined or display-only permission does not compile, a hand-written policy does`() {
        errors(app(catalog + ""","roles":[{"id":"a","permissions":["ghost"]}]""")).assert()
            .containsExactly("$.roles[0].permissions[0]: [ghost] is not a permission app wms defines")
        errors(app(catalog + ""","roles":[{"id":"a","permissions":["label"]}]""")).assert()
            .containsExactly("$.roles[0].permissions[0]: [label] is not a permission app wms defines")
        errors(
            app(
                ""","policies":[{"id":"perm:wms/x","statements":[{"effect":"deny","action":"*"}]}],"roles":[{"id":"a","permissions":["x"]}]"""
            )
        ).assert().isEmpty()
    }

    @Test
    fun `star takes what the catalog defines and leaves out the display-only entries`() {
        val written = app(
            """,
            "catalog":{"groups":[{"name":"g","permissions":[{"id":"hand"},{"id":"short","action":"a:b"},{"id":"label"}]}]},
            "policies":[{"id":"perm:wms/hand","statements":[{"effect":"allow","action":"a:c"}]},{"id":"perm:wms/other","statements":[{"effect":"allow","action":"a:d"}]}],
            "roles":[{"id":"a","permissions":"*"}]"""
        )
        errors(written).assert().isEmpty()
        RoleExpansion.effective(
            written
        ).bindings.map { it.id.value }.assert().containsExactly("role:a@wms:hand", "role:a@wms:short")
    }

    @Test
    fun `a permission has one definition, and a display-only entry keeps loading`() {
        errors(
            app(
                ""","catalog":{"groups":[{"name":"g","permissions":[{"id":"x","action":"a:b"}]}]},"policies":[{"id":"perm:wms/x","statements":[{"effect":"allow","action":"a:b"}]}]"""
            )
        ).assert().containsExactly(
            "$.catalog.groups[0].permissions[0].action: [x] has an action and a hand-written policy; a permission has one definition"
        )
        errors(
            app(""","catalog":{"groups":[{"name":"g","permissions":[{"id":"x","action":"a:b"},{"id":"x"}]}]}""")
        ).assert()
            .containsExactly("$.catalog.groups[0].permissions[0].id: [x] is listed twice and defined by an action")
        errors(app(""","catalog":{"groups":[{"name":"g","permissions":[{"id":"","action":"a:b"}]}]}""")).assert()
            .containsExactly("$.catalog.groups[0].permissions[0].id: a permission with an action has an id")
        errors(app(""","catalog":{"groups":[{"name":"g","permissions":[{"id":"x","resource":"a/b"}]}]}""")).assert()
            .containsExactly("$.catalog.groups[0].permissions[0].resource: a resource needs an action")
        // As published before `action` existed: a repeated display-only id, and an entry that shares a policy's id.
        errors(
            app(
                ""","catalog":{"groups":[{"name":"g","permissions":[{"id":"x"},{"id":"x"}]}]},"policies":[{"id":"perm:wms/x","statements":[{"effect":"allow","action":"a:b"}]}]"""
            )
        ).assert().isEmpty()
    }

    @Test
    fun `a bad action in a catalog entry is reported at the entry`() {
        errors(
            app(""","catalog":{"groups":[{"name":"g","permissions":[{"id":"x","action":"no-colon"}]}]}""")
        ).single().assert()
            .startsWith("$.catalog.groups[0].permissions[0].action[0]:")
    }

    @Test
    fun `role ids, apps and spaces are validated`() {
        fun tenantErrors(role: String) = errors(tenant(""","roles":[$role]"""))
        tenantErrors("""{"id":"","app":"wms","permissions":[]}""").single().assert().contains("must not be empty")
        tenantErrors("""{"id":"*","app":"wms","permissions":[]}""").single().assert().contains("must not be [*]")
        tenantErrors("""{"id":"a@b","app":"wms","permissions":[]}""").single().assert().contains("has no [@]")
        tenantErrors("""{"id":"a","app":"a:b","permissions":[]}""").single().assert().contains("cannot be an app id")
        tenantErrors("""{"id":"a","app":"wms","space":"(x)","permissions":[]}""").single().assert().contains("reserved")
        tenantErrors(
            """{"id":"a","app":"wms","permissions":["*"]}"""
        ).single().assert().contains("is not a permission id")
        tenantErrors("""{"id":"a","app":"wms","permissions":["x","x"]}""").single().assert().contains("listed twice")
        tenantErrors(
            """{"id":"a","app":"wms","permissions":[]},{"id":"a","app":"pos","permissions":[]}"""
        ).single().assert().contains("defined twice")
        tenantErrors("""{"id":"a","app":"wms","permissions":["x"]}""").assert().isEmpty()
    }

    @Test
    fun `a derived binding that collides with a hand-written one does not compile`() {
        errors(
            tenant(
                ""","roles":[{"id":"a","app":"wms","permissions":["x"]}],"bindings":[{"id":"role:a@wms:x","subject":{"role":"a"},"app":"wms","space":"*","policy":"perm:wms/x"}]"""
            )
        ).assert().containsExactly("$.roles[0].permissions: binding [role:a@wms:x] is also written by hand")
    }

    @Test
    fun `a hand-written binding of a short-form permission compiles`() {
        errors(
            app(
                catalog + ""","bindings":[{"id":"b","subject":{"role":"r"},"app":"wms","space":"*","policy":"perm:wms/read"}]"""
            )
        ).assert().isEmpty()
    }

    @Test
    fun `the roles of a document derive at most the limit, counted before anything is derived`() {
        val limited = PolicyDocumentCompiler(roleGrantLimit = 3)
        val permissions = (1..2).joinToString(",") { """{"id":"p$it","action":"a:b"}""" }
        val body = ""","catalog":{"groups":[{"name":"g","permissions":[$permissions]}]},"roles":[{"id":"a","permissions":"*"},{"id":"b","permissions":"*"}]"""
        RoleExpansion.grants(app(body)).assert().isEqualTo(4)
        val error = assertThrownBy<DocumentCompileException> { limited.compile(app(body)) }.actual()
        (error as DocumentCompileException).errors.single().let {
            it.path.assert().isEqualTo("$.roles")
            it.message.assert().contains("4 bindings, more than 3")
        }
        PolicyDocumentCompiler(roleGrantLimit = 4).compile(app(body)).assert().isNotNull()
        RoleExpansion.DEFAULT_ROLE_GRANT_LIMIT.assert().isEqualTo(50_000)
    }
}
