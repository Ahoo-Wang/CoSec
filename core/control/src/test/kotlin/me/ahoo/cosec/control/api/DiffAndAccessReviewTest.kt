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
import me.ahoo.cosec.control.change.ChangeAuthority
import me.ahoo.cosec.control.change.ChangeHarness
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.PERMIT_ALL
import me.ahoo.cosec.control.change.PLATO
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.flat.RateLimitSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.runtime.document.Catalog
import me.ahoo.cosec.runtime.document.CatalogGroup
import me.ahoo.cosec.runtime.document.CatalogPermission
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * #1205: a revision diff reports a change of the sections (catalog, limits, attributes, boundary, rate limits) by their
 * whole content, not by their ids or sizes. #1206: an access review of a tenant lists only the bindings that can apply
 * in that tenant, by the engine's rule ([SubjectRef]): a principal binding in its principal's tenant, a role binding
 * with a tenant only there, a role binding without one and `Anyone` everywhere.
 */
class DiffAndAccessReviewTest {
    private val harness = ChangeHarness()
    private val revisions = harness.revisions
    private val plane = DefaultControlPlane(
        harness.changes,
        revisions,
        ChangeAuthority(PERMIT_ALL, ChangeOptions(shadow = IMMEDIATE_WINDOW)),
        Simulator(),
    )
    private val acme = SnapshotKey.Tenant("acme")
    private val order = SnapshotKey.App("order")

    private fun publish(document: PolicyDocument, key: SnapshotKey) = runBlocking {
        revisions.publish(key, revisions.head(key), document, StoreFixtures.publication())
    }

    private fun document(
        key: SnapshotKey,
        policies: List<PolicyDefinition> = emptyList(),
        bindings: List<Binding> = emptyList(),
        catalog: Catalog? = null,
        boundary: PolicyId? = null,
        limits: ConditionLimits? = null,
        attributes: List<Attribute>? = null,
        rateLimits: List<RateLimitSource> = emptyList(),
    ): PolicyDocument {
        val (kind, owner) = Revisions.ownerOf(key)
        return PolicyDocument(
            kind,
            owner,
            1,
            StoreFixtures.START,
            policies,
            bindings,
            catalog = catalog,
            boundary = boundary,
            limits = limits,
            attributes = attributes,
            rateLimits = rateLimits,
        )
    }

    private fun quota(limit: Double, windowSeconds: Long = 1) = RateLimitSource(
        "quota",
        StatementSource("", Effect.ALLOW, listOf("*")),
        key = null,
        limit = RateLimitSpec(limit, windowSeconds),
    )

    private fun sectionsChanged(key: SnapshotKey, before: PolicyDocument, after: PolicyDocument): Boolean {
        publish(before, key)
        publish(after, key)
        val head = runBlocking { revisions.head(key) }!!.revision
        return runBlocking { plane.diff(PLATO, key, head - 1, head) }.sections
    }

    @Test
    fun `a rate limit rule of the same id with another quota changes the sections - #1205`() {
        sectionsChanged(
            acme,
            document(acme, rateLimits = listOf(quota(1.0))),
            document(acme, rateLimits = listOf(quota(100.0)))
        )
            .assert().isTrue()
    }

    @Test
    fun `a rate limit rule of the same id and quota with another window changes the sections`() {
        sectionsChanged(
            acme,
            document(acme, rateLimits = listOf(quota(10.0, windowSeconds = 1))),
            document(acme, rateLimits = listOf(quota(10.0, windowSeconds = 60))),
        ).assert().isTrue()
    }

    @Test
    fun `limits other than the cost, an attribute's type and the boundary change the sections`() {
        val platform = SnapshotKey.Platform
        val boundary =
            PolicyDefinition(
                PolicyId("fence"),
                false,
                null,
                listOf(StatementSource("", Effect.ALLOW, listOf("*"))),
                boundary = true
            )
        val other =
            PolicyDefinition(
                PolicyId("fence-2"),
                false,
                null,
                listOf(StatementSource("", Effect.ALLOW, listOf("*"))),
                boundary = true
            )
        sectionsChanged(
            platform,
            document(platform, listOf(boundary, other), limits = ConditionLimits(maxDepth = 8)),
            document(platform, listOf(boundary, other), limits = ConditionLimits(maxDepth = 4)),
        ).assert().isTrue()
        sectionsChanged(
            platform,
            document(
                platform,
                listOf(boundary, other),
                attributes = listOf(Attribute.external("ext.geo.region", AttributeType.STRING))
            ),
            document(
                platform,
                listOf(boundary, other),
                attributes = listOf(Attribute.external("ext.geo.region", AttributeType.STRING_SET))
            ),
        ).assert().isTrue()
        sectionsChanged(
            platform,
            document(platform, listOf(boundary, other), boundary = PolicyId("fence")),
            document(platform, listOf(boundary, other), boundary = PolicyId("fence-2")),
        ).assert().isTrue()
    }

    @Test
    fun `a catalog of as many groups with other permissions changes the sections`() {
        fun catalog(
            permission: String
        ) = Catalog(listOf(CatalogGroup("orders", permissions = listOf(CatalogPermission(permission)))))
        sectionsChanged(
            order,
            document(order, catalog = catalog("order:read")),
            document(order, catalog = catalog("order:write")),
        ).assert().isTrue()
    }

    @Test
    fun `the same sections in another order of policies do not change the sections`() {
        sectionsChanged(
            acme,
            document(acme, listOf(allow("a")), rateLimits = listOf(quota(5.0))),
            document(acme, listOf(allow("a"), allow("b")), rateLimits = listOf(quota(5.0))),
        ).assert().isFalse()
    }

    private val read = Action("order", "read")
    private val query = AccessQuery(
        tenant = TenantId("acme"),
        app = "order",
        space = "",
        action = read,
        resource = Resource("order", "42", emptyMap()),
    )

    private fun bound(id: String, subject: SubjectRef) =
        Binding(BindingId(id), subject, BindingScope(Match.Any, Match.Any), PolicyId("read"))

    @Test
    fun `an access review of a tenant leaves out what only another tenant's subjects are bound to - #1206`() {
        publish(
            document(
                SnapshotKey.Platform,
                listOf(allow("read")),
                listOf(
                    bound("globex-only", SubjectRef.Principal(TenantId("globex"), PrincipalId("bob"))),
                    bound("acme-bob", SubjectRef.Principal(TenantId("acme"), PrincipalId("bob"))),
                    bound("anyone", SubjectRef.Anyone),
                ),
            ),
            SnapshotKey.Platform,
        )
        publish(
            document(
                order,
                listOf(allow("read")),
                listOf(
                    bound("any-tenant-editor", SubjectRef.Role(null, RoleId("editor"))),
                    bound("acme-editor", SubjectRef.Role(TenantId("acme"), RoleId("editor"))),
                    bound("globex-editor", SubjectRef.Role(TenantId("globex"), RoleId("editor"))),
                ),
            ),
            order,
        )
        val report = runBlocking { plane.analyze(PLATO, query) }
        report.allows.map { it.binding }.toSet().assert()
            .isEqualTo(setOf("acme-bob", "anyone", "any-tenant-editor", "acme-editor"))
        runBlocking { plane.analyze(PLATO, query.copy(tenant = TenantId("globex"))) }
            .allows.map { it.binding }.toSet().assert()
            .isEqualTo(setOf("globex-only", "anyone", "any-tenant-editor", "globex-editor"))
    }

    @Test
    fun `a tenant's own role bindings stay in its access review`() {
        publish(
            document(
                acme,
                listOf(allow("read")),
                listOf(
                    bound("own-editor", SubjectRef.Role(TenantId("acme"), RoleId("editor"))),
                    bound("own-bob", SubjectRef.Principal(TenantId("acme"), PrincipalId("bob"))),
                ),
            ),
            acme,
        )
        runBlocking { plane.analyze(PLATO, query) }.allows.map { it.binding }.toSet().assert()
            .isEqualTo(setOf("own-editor", "own-bob"))
    }
}
