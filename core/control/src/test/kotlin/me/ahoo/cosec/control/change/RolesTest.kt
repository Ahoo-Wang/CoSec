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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.AccessQuery
import me.ahoo.cosec.control.api.DefaultControlPlane
import me.ahoo.cosec.control.api.DocumentCache
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.grant.GrantDocuments
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.Catalog
import me.ahoo.cosec.runtime.document.CatalogGroup
import me.ahoo.cosec.runtime.document.CatalogPermission
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.RoleDefinition
import me.ahoo.cosec.runtime.document.RolePermissions
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

/** Roles on the control plane (RFC 0012 §7): operations, authorization, checks and what they read. */
class RolesTest {
    private val harness = ChangeHarness()
    private val changes = harness.changes
    private val wms = SnapshotKey.App("wms")

    private val catalog = Catalog(
        listOf(
            CatalogGroup("ops", permissions = listOf(CatalogPermission("read", actions = listOf("wms:read")))),
            CatalogGroup(
                "in",
                spaced = true,
                permissions = listOf(CatalogPermission("ship", actions = listOf("wms:ship")))
            ),
        ),
    )

    private fun custom(id: String, vararg permissions: String, space: SpaceId? = null) = RoleDefinition(
        RoleId(id),
        RolePermissions.Listed(permissions.toList()),
        app = AppId("wms"),
        space = space,
    )

    private fun system(id: String, permissions: RolePermissions) = RoleDefinition(RoleId(id), permissions)

    private suspend fun publish(key: SnapshotKey, vararg operations: Operation): ChangeRequest {
        val change = changes.propose(
            operatorFor(key),
            key,
            harness.revisions.head(key)?.revision ?: 0,
            operations.toList(),
            "test"
        )
        change.errors.assert().describedAs("${change.findings}").isEmpty()
        changes.skipShadow(operatorFor(key), change.id, "test")
        return changes.publish(operatorFor(key), change.id)
    }

    private suspend fun app(vararg roles: RoleDefinition) =
        publish(wms, Operation.PutCatalog(catalog), *roles.map { Operation.PutRole(it) }.toTypedArray())

    private fun applied(base: PolicyDocument?, vararg operations: Operation): Applied =
        Operations.apply(ACME, base, operations.toList())

    @Test
    fun `put and remove keep the role section, guarded by its digest`() {
        val role = custom("night", "read")
        val first = applied(null, Operation.PutRole(role)).candidate!!
        first.roles.single().id.assert().isEqualTo(role.id)
        val replaced = applied(first, Operation.PutRole(custom("night", "ship"), EntryDigest.of(role))).candidate!!
        (replaced.roles.single().permissions as RolePermissions.Listed).ids.assert().containsExactly("ship")
        applied(first, Operation.PutRole(custom("night"), "stale")).findings.single().let {
            it.check.assert().isEqualTo("IF_MATCH")
            it.status.assert().isEqualTo(412)
        }
        applied(first, Operation.RemoveRole(role.id, EntryDigest.of(role))).candidate!!.roles.assert().isEmpty()
        applied(first, Operation.RemoveRole(RoleId("ghost"))).findings.single().status.assert().isEqualTo(422)
        applied(first, Operation.RemoveRole(role.id, "stale")).findings.single().check.assert().isEqualTo("IF_MATCH")
    }

    @Test
    fun `a role that does not fit its document is an operation finding`() {
        applied(
            null,
            Operation.PutRole(RoleDefinition(RoleId("a"), RolePermissions.Listed(emptyList())))
        ).findings.single()
            .message.assert().contains("names its app")
        Operations.apply(wms, null, listOf(Operation.PutRole(custom("a")))).findings.single()
            .message.assert().contains("names no app or space")
        Operations.apply(SnapshotKey.Platform, null, listOf(Operation.PutRole(custom("a")))).findings.single()
            .message.assert().contains("no roles")
    }

    @Test
    fun `the candidate digest covers roles and apply merges or replaces them`() {
        val base = applied(
            null,
            Operation.PutRole(custom("a", "read")),
            Operation.PutRole(custom("b", "read"))
        ).candidate!!
        val desired = applied(
            null,
            Operation.PutRole(custom("a", "ship")),
            Operation.PutRole(custom("c", "read"))
        ).candidate!!
        CandidateDigest.of(base).assert().isNotEqualTo(CandidateDigest.of(desired))
        Operations.diff(ACME, base, desired, ApplyMode.MERGE).map { it::class.simpleName }.assert()
            .containsExactly("PutRole", "PutRole")
        val replace = Operations.diff(ACME, base, desired, ApplyMode.REPLACE)
        replace.map { it::class.simpleName }.assert().containsExactly("PutRole", "PutRole", "RemoveRole")
        (replace[0] as Operation.PutRole).ifMatch.assert().isEqualTo(EntryDigest.of(base.roles[0]))
        (replace[1] as Operation.PutRole).ifMatch.assert().isNull()
        (replace[2] as Operation.RemoveRole).ifMatch.assert().isEqualTo(EntryDigest.of(base.roles[1]))
        Operations.diff(ACME, desired, desired, ApplyMode.REPLACE).assert().isEmpty()
    }

    @Test
    fun `a change's role operations read back as written`() {
        val now = Instant.parse("2026-11-01T00:00:00Z")
        val role = custom("night", "read", space = SpaceId("wh-01"))
        val written = ChangeRequest(
            ChangeId("c1"), ACME, Base.NONE,
            listOf(Operation.PutRole(role, "d1"), Operation.RemoveRole(RoleId("old"), "d2")),
            ALICE, "note", me.ahoo.cosec.runtime.document.ProvenanceKind.PUBLISH, ChangeState.DRAFT,
            emptyList(), null, emptyMap(), now, now,
        )
        val read = ChangeRequestCodec.decode(ChangeRequestCodec.encode(written))
        (read.operations[0] as Operation.PutRole).let {
            EntryDigest.of(it.role).assert().isEqualTo(EntryDigest.of(role))
            it.ifMatch.assert().isEqualTo("d1")
        }
        (read.operations[1] as Operation.RemoveRole).id.assert().isEqualTo(RoleId("old"))
        val node = CoSecJson.mapper.readTree(
            """{"op":"putRole","role":{"id":"r","app":"wms","permissions":[]},"ifMatch":"d"}"""
        )
        (ChangeRequestCodec.operation(ACME, node) as Operation.PutRole).ifMatch.assert().isEqualTo("d")
    }

    private fun recording(): Pair<ChangeAuthority, MutableList<String>> {
        val asked = ArrayList<String>()
        val authorizer = ControlAuthorizer { _, action, resource ->
            asked += "${action.verb} ${resource.type}/${resource.id}"
            ControlDecision.PERMIT
        }
        return ChangeAuthority(authorizer) to asked
    }

    private fun authorized(base: PolicyDocument?, operator: Operator, key: SnapshotKey, vararg operations: Operation): List<String> {
        val (authority, asked) = recording()
        runBlocking { authority.authorize(operator, key, operations.toList(), base = base) }
        return asked
    }

    @Test
    fun `cosec grant is asked once, only when a role derives a binding it did not`() {
        val base = applied(null, Operation.PutRole(custom("night", "read", "ship"))).candidate!!
        val write = "write doc/tenant/acme/role/night"
        val grant = "grant scope/acme/wms/*"
        authorized(base, ALICE_OP, ACME, Operation.PutRole(custom("night", "read"))).assert().containsExactly(write)
        authorized(base, ALICE_OP, ACME, Operation.PutRole(custom("night", "read", "ship", "more"))).assert()
            .containsExactly(write, grant)
        authorized(
            null,
            ALICE_OP,
            ACME,
            Operation.PutRole(custom("night", "read", "ship"))
        ).assert().containsExactly(write, grant)
        authorized(base, ALICE_OP, ACME, Operation.PutRole(custom("night", "read", space = SpaceId("wh-01")))).assert()
            .containsExactly(write, "grant scope/acme/wms/wh-01")
        authorized(base, ALICE_OP, ACME, Operation.PutRole(custom("night"))).assert().containsExactly(write)
        authorized(base, ALICE_OP, ACME, Operation.RemoveRole(RoleId("night"))).assert().containsExactly(write)
        // A new role with no permissions grants nothing.
        authorized(
            base,
            ALICE_OP,
            ACME,
            Operation.PutRole(custom("draft"))
        ).assert().containsExactly("write doc/tenant/acme/role/draft")
    }

    @Test
    fun `star adds what the list did not have, and a role that keeps it adds nothing`() {
        val before = Operations.apply(
            wms,
            null,
            listOf(
                Operation.PutCatalog(catalog),
                Operation.PutRole(system("a", RolePermissions.Listed(listOf("read"))))
            )
        )
            .candidate!!
        val all = system("a", RolePermissions.All)
        authorized(before, PLATO, wms, Operation.PutRole(all)).assert()
            .containsExactly("write doc/app/wms/role/a", "grant scope/*/wms/*")
        val withAll = Operations.apply(wms, before, listOf(Operation.PutRole(all))).candidate!!
        authorized(withAll, PLATO, wms, Operation.PutRole(all)).assert().containsExactly("write doc/app/wms/role/a")
    }

    /** RFC 0012 §7.2: what `cosec:grant` is asked for is what the candidate derives, not what the base's catalog would. */
    @Test
    fun `a star role put with the catalog that defines its permissions is asked cosec grant for`() {
        authorized(null, PLATO, wms, Operation.PutCatalog(catalog), Operation.PutRole(system("a", RolePermissions.All)))
            .assert().containsExactly("write doc/app/wms/catalog", "write doc/app/wms/role/a", "grant scope/*/wms/*")
    }

    @Test
    fun `a catalog that gives a star role a binding it did not derive is asked cosec grant for`() {
        val base = Operations.apply(wms, null, listOf(Operation.PutRole(system("a", RolePermissions.All)))).candidate!!
        authorized(base, PLATO, wms, Operation.PutCatalog(catalog)).assert()
            .containsExactly("write doc/app/wms/catalog", "grant scope/*/wms/*")
        val full = Operations.apply(wms, base, listOf(Operation.PutCatalog(catalog))).candidate!!
        // The same catalog, and one that only takes permissions away, add no binding.
        authorized(
            full,
            PLATO,
            wms,
            Operation.PutCatalog(catalog)
        ).assert().containsExactly("write doc/app/wms/catalog")
        authorized(full, PLATO, wms, Operation.PutCatalog(null)).assert().containsExactly("write doc/app/wms/catalog")
    }

    @Test
    fun `a hand-written permission that gives a star role a binding is asked cosec grant for`() {
        val entry = Catalog(listOf(CatalogGroup("ops", permissions = listOf(CatalogPermission("audit")))))
        val base = Operations.apply(
            wms,
            null,
            listOf(Operation.PutCatalog(entry), Operation.PutRole(system("a", RolePermissions.All))),
        ).candidate!!
        authorized(base, PLATO, wms, Operation.PutPolicy(allow("perm:wms/audit"))).assert()
            .containsExactly("write doc/app/wms/policy/perm:wms/audit", "grant scope/*/wms/*")
    }

    @Test
    fun `removing a role needs no second person, and putting one does`() = runBlocking<Unit> {
        // Only the trust root publishes an app document alone; a platform principal who is not one needs approval.
        val notTrustRoot = ControlAuthorizer { _, action, _ ->
            if (action.verb == "approve") ControlDecision.DENY else ControlDecision.PERMIT
        }
        val authority = ChangeAuthority(notTrustRoot)
        val documents = object : ChangeChecks.Context {
            override suspend fun head(key: SnapshotKey) = null
            override suspend fun referrers(target: SnapshotKey, policy: PolicyId) = emptySet<SnapshotKey>()
            override suspend fun apps() = null
        }
        val now = Instant.parse("2026-11-01T00:00:00Z")
        val base = Operations.apply(
            wms,
            null,
            listOf(Operation.PutCatalog(catalog), Operation.PutRole(system("a", RolePermissions.All)))
        ).candidate!!
        val removing = listOf(Operation.RemoveRole(RoleId("a")))
        authority.escalations(
            PLATO,
            wms,
            removing,
            base,
            Operations.apply(wms, base, removing).candidate!!,
            documents,
            now
        ).assert().isEmpty()
        val putting = listOf(Operation.PutRole(system("b", RolePermissions.All)))
        authority.escalations(
            PLATO,
            wms,
            putting,
            base,
            Operations.apply(wms, base, putting).candidate!!,
            documents,
            now
        )
            .map { it.reason }.assert().containsExactly("NOT_ANALYZED")
    }

    @Test
    fun `a tenant's role warns when it shares a system role's id or lists what cannot apply in its space`() = runBlocking<Unit> {
        app(system("clerk", RolePermissions.Listed(listOf("read"))))
        val change = changes.propose(
            operatorFor(ACME),
            ACME,
            0,
            listOf(Operation.PutRole(custom("clerk", "read", "ship", space = SpaceId("wh-01")))),
            "warns",
        )
        change.errors.assert().isEmpty()
        change.findings.filter { it.severity == Severity.WARNING }.map { it.path }.assert()
            .containsExactly("$.roles[0].id", "$.roles[0].permissions[0]")
        val quiet = changes.propose(
            operatorFor(ACME),
            ACME,
            0,
            listOf(Operation.PutRole(custom("other", "read", space = SpaceId.DEFAULT))),
            "quiet"
        )
        quiet.findings.assert().isEmpty()
        val spaced = changes.propose(
            operatorFor(ACME),
            ACME,
            0,
            listOf(Operation.PutRole(custom("other", "ship", space = SpaceId("wh-01")))),
            "spaced"
        )
        spaced.findings.assert().isEmpty()
    }

    @Test
    fun `roles over the limit are a compile finding, and nothing expands them further`() = runBlocking<Unit> {
        val permissions = (1..300).map { CatalogPermission("p$it", actions = listOf("wms:read")) }
        val operations = listOf(Operation.PutCatalog(Catalog(listOf(CatalogGroup("g", permissions = permissions))))) +
            (1..300).map { Operation.PutRole(system("r$it", RolePermissions.All)) }
        val change = changes.propose(operatorFor(wms), wms, 0, operations, "too many")
        change.errors.map { it.check to it.path }.assert().containsExactly("COMPILE" to "$.roles")
    }

    @Test
    fun `a role survives a publish, a rollback and the document it is stored in`() = runBlocking<Unit> {
        app()
        publish(ACME, Operation.PutRole(custom("night", "read")))
        harness.revisions.head(ACME)!!.document.let {
            it.roles.single().id.assert().isEqualTo(RoleId("night"))
            it.bindings.assert().isEmpty()
        }
        publish(ACME, Operation.RemoveRole(RoleId("night")))
        val back = changes.rollback(operatorFor(ACME), ACME, 1, "back")
        back.errors.assert().isEmpty()
        changes.skipShadow(operatorFor(ACME), back.id, "test")
        changes.publish(operatorFor(ACME), back.id)
        harness.revisions.head(ACME)!!.document.roles.single().id.assert().isEqualTo(RoleId("night"))
    }

    @Test
    fun `the grant view, the access review and the tenant bindings of an app change read expanded documents`() = runBlocking<Unit> {
        app(system("clerk", RolePermissions.Listed(listOf("read"))))
        publish(ACME, Operation.PutRole(custom("night", "ship", space = SpaceId("wh-01"))))
        val head = harness.revisions.head(ACME)!!.document
        GrantDocuments.of(head).bindings.map { it.id.value }.assert().containsExactly("role:night@wms:ship")
        GrantDocuments.of(harness.revisions.head(wms)!!.document).let { document ->
            document.policies.map { it.id.value }.assert().containsExactly("perm:wms/read", "perm:wms/ship")
            document.bindings.map { it.id.value }.assert().containsExactly("role:clerk@wms:read")
        }
        val plane = DefaultControlPlane(changes, harness.revisions, ChangeAuthority(PERMIT_ALL))
        val report = plane.analyze(
            PLATO,
            AccessQuery(TenantId("acme"), "wms", "wh-01", Action("wms", "ship"), Resource("wms", "x", emptyMap())),
        )
        report.opaque.assert().isEqualTo(0)
        report.allows.map { it.binding }.assert().containsExactly("role:night@wms:ship")
        harness.revisions.referrers(wms, PolicyId("perm:wms/ship")).assert().containsExactly(ACME)
    }

    @Test
    fun `removing a hand-written binding of a short-form permission narrows`() = runBlocking<Unit> {
        val document = Operations.apply(wms, null, listOf(Operation.PutCatalog(catalog))).candidate!!
        val bound =
            PolicyDocument(
                document.kind,
                document.owner,
                1,
                document.publishedAt,
                emptyList(),
                listOf(bind("b", "perm:wms/read")),
                catalog
            )
        val candidate =
            PolicyDocument(document.kind, document.owner, 2, document.publishedAt, emptyList(), emptyList(), catalog)
        val operations = listOf(Operation.RemoveBinding(me.ahoo.cosec.model.BindingId("b")))
        Narrowing.only(bound, candidate, operations, policyOf = { null }).assert().isTrue()
    }

    @Test
    fun `a document weighs what its roles derive`() {
        fun cached(document: PolicyDocument): Long {
            val cache = DocumentCache()
            cache.put(wms, SnapshotDigest(ByteArray(32)), 100, document)
            return cache.estimatedHeap
        }
        val plain = StoreFixtures.candidate(wms)
        val roles = (1..100).map { system("r$it", RolePermissions.Listed(listOf("read"))) }
        val document =
            PolicyDocument(plain.kind, plain.owner, 1, plain.publishedAt, emptyList(), emptyList(), roles = roles)
        val derived = 100 * DocumentCache.DERIVED_BINDING_BYTES * DocumentCache.DECODED_FACTOR
        cached(document).assert().isEqualTo(cached(plain) + derived)
        PolicyDocumentCodec.encode(document).decodeToString().assert().contains("\"roles\"")
        Revisions.ownerOf(wms).second.assert().isEqualTo("wms")
    }
}
