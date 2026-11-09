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
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.CompiledDocument
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
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * ROL-1 (RFC 0012 §10): random app and tenant documents with roles and short-form permissions decide exactly as those
 * documents do with their [RoleExpansion.effective] form written out and read back, so nothing about a decision comes
 * from the short form itself.
 */
class RoleExpansionPropertyTest {
    private val iterations = System.getProperty("cosec.property.iterations")?.toInt() ?: 1_500
    private val compiler = PolicyDocumentCompiler()
    private val platform = compiler.compile(PolicyDocument(DocumentKind.PLATFORM, "", 1, NOW, emptyList(), emptyList()))

    @Test
    fun `ROL-1 a document with roles decides as its effective form written out`() {
        val random = Random(20261108)
        val outcomes = HashSet<Class<*>>()
        repeat(iterations) { iteration ->
            val apps = APPS.associateWith { app(random, it) }
            val tenant = tenant(random)
            val short = apps.mapValues { compiler.compile(it.value) } to compiler.compile(tenant)
            val written = apps.mapValues { compiler.compile(writtenOut(it.value)) } to compiler.compile(writtenOut(tenant))
            repeat(REQUESTS_PER_CASE) {
                val request = request(random) ?: return@repeat
                val decision = decide(short, request)
                outcomes += decision.javaClass
                decision.assert().describedAs("case $iteration").isEqualTo(decide(written, request))
            }
        }
        // The generator reaches a permit and a deny, or the property says nothing.
        outcomes.assert().contains(Decision.Permit::class.java, Decision.Deny::class.java)
    }

    /** The document as a reader of [RoleExpansion.effective] would have written it: no roles, no short forms. */
    private fun writtenOut(document: PolicyDocument): PolicyDocument {
        val effective = RoleExpansion.effective(document)
        effective.roles.assert().isEmpty()
        RoleExpansion.effective(effective).policies.size.assert().isEqualTo(effective.policies.size)
        return PolicyDocumentCodec.decode(document.kind, document.owner, PolicyDocumentCodec.encode(effective))
    }

    private fun app(random: Random, app: String): PolicyDocument {
        val groups = listOf(true, false).mapIndexed { g, spaced ->
            val permissions = (0 until random.nextInt(1, 4)).map { p ->
                val id = "p$g$p"
                when (random.nextInt(4)) {
                    0 -> CatalogPermission(id)
                    else -> CatalogPermission(
                        id,
                        actions = listOf(ACTIONS.random(random)),
                        resources = resources(random)
                    )
                }
            }
            CatalogGroup("g$g", spaced = spaced, permissions = permissions)
        }
        val defined = groups.flatMap { it.permissions }.filter { it.actions != null }.map { it.id } + "written"
        val written = PolicyDocumentCodec.decodePolicy(
            CoSecJsonTree.of(
                """{"id":"perm:$app/written","statements":[{"effect":"deny","action":"${ACTIONS.random(
                    random
                )}","resource":"order/9"}]}"""
            )
        )
        val roles = ROLES.take(random.nextInt(ROLES.size + 1)).map { role ->
            val permissions = if (random.nextInt(4) == 0) {
                RolePermissions.All
            } else {
                RolePermissions.Listed(defined.shuffled(random).take(random.nextInt(defined.size + 1)))
            }
            RoleDefinition(RoleId(role), permissions)
        }
        return PolicyDocument(
            DocumentKind.APP,
            app,
            1,
            NOW,
            listOf(written),
            emptyList(),
            Catalog(groups),
            roles = roles
        )
    }

    private fun tenant(random: Random): PolicyDocument {
        val roles = ROLES.take(random.nextInt(ROLES.size + 1)).map { role ->
            val listed = (0..1).flatMap { g -> (0..2).map { "p$g$it" } }.shuffled(random).take(random.nextInt(4))
            RoleDefinition(
                RoleId("custom-$role"),
                RolePermissions.Listed(listed),
                app = me.ahoo.cosec.model.AppId(APPS.random(random)),
                space = listOf(null, SpaceId.DEFAULT, SpaceId("s1")).random(random),
            )
        }
        return PolicyDocument(DocumentKind.TENANT, TENANT.value, 1, NOW, emptyList(), emptyList(), roles = roles)
    }

    private fun resources(random: Random): List<me.ahoo.cosec.engine.flat.ResourceSource> =
        if (random.nextBoolean()) emptyList() else listOf(me.ahoo.cosec.engine.flat.ResourceSource("order/{id}"))

    private class Asked(val memberships: Memberships, val app: String, val space: String, val action: Action)

    private fun request(random: Random): Asked? {
        val held = (ROLES + ROLES.map { "custom-$it" }).shuffled(random).take(random.nextInt(4))
        val roles = held.groupBy { listOf(SpaceId.DEFAULT, SpaceId("s1")).random(random) }
            .mapValues { (_, names) -> names.mapTo(LinkedHashSet(), ::RoleId) }
        val action = ACTIONS.random(random).split(':').let { Action(it[0], it[1]) }
        return Asked(
            Memberships(Memberships.Source.CLAIMS, roles, emptyList()),
            APPS.random(random),
            listOf("", "s1").random(random),
            action,
        )
    }

    private fun decide(documents: Pair<Map<String, CompiledDocument>, CompiledDocument>, asked: Asked): Decision {
        val resolved = ScopeResolver.resolve(
            ScopeSubject(TENANT, true, asked.memberships, asked.app.ifEmpty { null }),
            asked.space,
            HostScope(null),
        )
        val scope = (resolved as? ScopeResolution.Resolved)?.scope ?: return Decision.Deny(null)
        val subject = Subject("alice", true, emptyMap(), principalBindings = true, breakGlass = true)
        val request = AuthzRequest(
            subject,
            asked.action,
            Resource("order", "9", emptyMap()),
            Environment("", "r", "", false)
        )
        val view = SnapshotView(platform, documents.first.getValue(asked.app), documents.second)
        return CoSecFlatEngine.evaluate(
            FlatRequest(request, scope, asked.memberships, 0),
            view,
            TraceLevel.FULL
        ).decision
    }

    private companion object {
        const val REQUESTS_PER_CASE = 8
        val NOW: java.time.Instant = java.time.Instant.parse("2026-11-01T00:00:00Z")
        val TENANT = TenantId("acme")
        val APPS = listOf("wms", "pos")
        val ROLES = listOf("clerk", "keeper", "admin")
        val ACTIONS = listOf("order:read", "order:write", "stock:read")
    }
}

private object CoSecJsonTree {
    fun of(json: String): tools.jackson.databind.JsonNode = me.ahoo.cosec.runtime.json.CoSecJson.mapper.readTree(json)
}
