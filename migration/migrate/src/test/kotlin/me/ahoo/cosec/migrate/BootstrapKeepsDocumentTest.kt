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

package me.ahoo.cosec.migrate

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.engine.BuiltinPolicies
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * MIG-3: `bootstrap` adds its binding to the platform tenant's document and keeps everything else it holds. A migration
 * that converts 7.x roles into that document and then bootstraps must not lose them: every member of a lost role is
 * denied. The base holds every member a platform tenant's document may hold; it names no boundary, which the compiler
 * refuses there, and a tenant document has no catalog, limits or attributes.
 */
class BootstrapKeepsDocumentTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)

    private val rule = """{"id":"login","action":"http:POST","resource":"http:path:/login","key":"environment.ip",""" +
        """"permitsPerSecond":0.5,"windowSeconds":60,"strictFailure":true}"""
    private val admin = """,{"id":"root","subject":{"principal":"alice","tenant":"(platform)"},"app":"*",""" +
        """"space":"*","policy":"cosec:platform-admin"}"""

    private fun base(withAdmin: Boolean): PolicyDocument = PolicyDocumentCodec.decode(
        DocumentKind.TENANT,
        TenantId.PLATFORM.value,
        (
            """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z",""" +
                """"roles":[{"id":"picker","app":"wms","space":"","permissions":["pick"]}],""" +
                """"policies":[{"id":"own","statements":[{"effect":"allow","action":"wms:pick"}]}],""" +
                """"bindings":[{"id":"o","subject":{"role":"picker"},"app":"wms","space":"*","policy":"own"}""" +
                (if (withAdmin) admin else "") +
                """],"rateLimits":[$rule]}"""
            ).encodeToByteArray(),
    )

    private fun fields(): List<Field> =
        PolicyDocument::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }

    /** Set: not null, not an empty collection, not an empty string. */
    private fun set(value: Any?): Boolean = when (value) {
        null -> false
        is Collection<*> -> value.isNotEmpty()
        is String -> value.isNotEmpty()
        else -> true
    }

    private suspend fun seed(document: PolicyDocument): PolicyDocument {
        val outcome = revisions.publish(
            Bootstrap.KEY,
            null,
            document,
            Publication("seed", ProvenanceKind.MIGRATE, DocumentPublisher.MIGRATE_ACTOR),
        )
        (outcome is PublishOutcome.Published).assert().isTrue()
        return revisions.head(Bootstrap.KEY)!!.document
    }

    /**
     * The document as the store holds it, without its header and its bindings. Two reads of the store decode to
     * different objects, so the documents are compared by what they encode to.
     */
    private fun stored(document: PolicyDocument): ObjectNode =
        CoSecJson.mapper.readTree(PolicyDocumentCodec.encode(document)) as ObjectNode

    private fun rest(document: PolicyDocument): ObjectNode = stored(document).apply {
        listOf("revision", "publishedAt", "provenance", "bindings").forEach { remove(it) }
    }

    /** What bootstrap changed: the header and the bindings, by the binding of [added] alone. */
    private suspend fun assertKept(before: PolicyDocument, added: String) {
        val after = revisions.head(Bootstrap.KEY)!!.document
        rest(after).assert().isEqualTo(rest(before))
        rest(before).propertyNames().asSequence().toList()
            .assert().contains("policies", "rateLimits", "roles")
        after.revision.assert().isEqualTo(before.revision + 1)
        after.provenance!!.kind.assert().isEqualTo(ProvenanceKind.BOOTSTRAP)
        stored(after)["bindings"].toList().dropLast(1).assert().isEqualTo(stored(before)["bindings"].toList())
        after.bindings.size.assert().isEqualTo(before.bindings.size + 1)
        after.bindings.last().subject.assert().isEqualTo(SubjectRef.Principal(TenantId.PLATFORM, PrincipalId(added)))
        after.bindings.last().policy.assert().isEqualTo(BuiltinPolicies.PLATFORM_ADMIN)
    }

    @Test
    fun `the base holds every member a platform tenant's document may hold`() = runBlocking<Unit> {
        val before = seed(base(withAdmin = false))
        fields().filter { !set(it.get(before)) }.map { it.name }
            .assert().containsExactlyInAnyOrder("catalog", "boundary", "limits", "attributes")
    }

    @Test
    fun `bootstrap adds its binding and keeps the rest of the document`() = runBlocking<Unit> {
        val before = seed(base(withAdmin = false))
        Bootstrap(store, clock).run(PrincipalId("bob")).assert().isEqualTo(before.revision + 1)
        assertKept(before, "bob")
    }

    @Test
    fun `a forced bootstrap adds its binding and keeps the rest of the document`() = runBlocking<Unit> {
        val before = seed(base(withAdmin = true))
        Bootstrap(store, clock).run(PrincipalId("bob"), force = true).assert().isEqualTo(before.revision + 1)
        assertKept(before, "bob")
    }
}
