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

package me.ahoo.cosec.control.local

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ChangeAnswer
import me.ahoo.cosec.control.api.ChangeSamples
import me.ahoo.cosec.control.api.ControlPlaneTest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.OperationCodec
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.control.sdk.ApplyMode
import me.ahoo.cosec.control.sdk.ApplyRequest
import me.ahoo.cosec.control.sdk.Binding
import me.ahoo.cosec.control.sdk.Catalog
import me.ahoo.cosec.control.sdk.CatalogGroup
import me.ahoo.cosec.control.sdk.CatalogPermission
import me.ahoo.cosec.control.sdk.ChangeRequest
import me.ahoo.cosec.control.sdk.ChangeState
import me.ahoo.cosec.control.sdk.Condition
import me.ahoo.cosec.control.sdk.ConflictKind
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.control.sdk.ControlClient
import me.ahoo.cosec.control.sdk.ControlRefusedException
import me.ahoo.cosec.control.sdk.ControlService
import me.ahoo.cosec.control.sdk.DocumentContent
import me.ahoo.cosec.control.sdk.Effect
import me.ahoo.cosec.control.sdk.Operation
import me.ahoo.cosec.control.sdk.Permissions
import me.ahoo.cosec.control.sdk.Policy
import me.ahoo.cosec.control.sdk.RateLimitRule
import me.ahoo.cosec.control.sdk.Resource
import me.ahoo.cosec.control.sdk.Role
import me.ahoo.cosec.control.sdk.Statement
import me.ahoo.cosec.control.sdk.Subject
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * `CTL-76`: what the typed layer writes is what the control plane reads, and what the control plane answers reads into
 * the typed objects. Each kind of operation goes through the control plane's own codec and back unchanged, as does every
 * change body of the wiki's samples; every change the answer codec writes reads; and the typed calls work end to end
 * over the in-process implementation.
 */
class TypedClientTest {
    private val acme = SnapshotKey.Tenant("acme")
    private val wms = SnapshotKey.App("wms")
    private val anyone = ControlCaller.service("svc-iam")

    /** A service that records each call's body and answers every call with [answer]. */
    private class Recorded(private val answer: () -> String) {
        val bodies = mutableListOf<String>()
        val service: ControlService = Proxy.newProxyInstance(
            ControlService::class.java.classLoader,
            arrayOf(ControlService::class.java),
        ) { _, method, args ->
            // A suspend function's last argument is its continuation; a body, where a call has one, is a String.
            args.orEmpty().dropLast(1).filterIsInstance<String>().filter { it.startsWith("{") }.forEach(bodies::add)
            check(method.returnType == Any::class.java)
            answer()
        } as ControlService
    }

    private fun changeAnswer(key: SnapshotKey, operations: List<JsonNode>): String {
        val tree = ChangeAnswer.tree(ChangeSamples.minimal.copy(key = key))
        tree.putArray("operations").addAll(operations)
        return CoSecJson.mapper.writeValueAsString(tree)
    }

    /** [operation] as the typed layer writes it into a proposal of [key]. */
    private fun written(key: SnapshotKey, operation: Operation): JsonNode {
        val recorded = Recorded { changeAnswer(key, emptyList()) }
        runBlocking { ControlClient(recorded.service).propose(anyone, ChangeRequest(key, 1, listOf(operation), "n")) }
        val body = CoSecJson.mapper.readTree(recorded.bodies.single())
        body.get("document").asString().assert().isEqualTo(key.toString())
        body.get("base").asLong().assert().isEqualTo(1L)
        body.get("note").asString().assert().isEqualTo("n")
        return body.get("operations").single()
    }

    /** [node] as the typed layer reads it from a change of [key]. */
    private fun read(key: SnapshotKey, node: JsonNode): Operation {
        val recorded = Recorded { changeAnswer(key, listOf(node)) }
        return runBlocking { ControlClient(recorded.service).change(anyone, "c1") }.operations.single()
    }

    /** [node] as the control plane reads it and writes it back. */
    private fun throughControlPlane(key: SnapshotKey, node: JsonNode): JsonNode {
        val (kind, owner) = ownerOf(key)
        return OperationCodec.tree(ChangeRequestCodec.operation(key, node), kind, owner)
    }

    @Test
    fun `each kind of operation reaches the control plane and comes back as it was written - CTL-76`() {
        val condition = Condition.of("""{"eq":["subject.tenant",{"attr":"resource.var.t"}]}""")
        val policy = Policy(
            "typed",
            listOf(
                Statement(
                    Effect.ALLOW,
                    listOf("order:read", "order:list"),
                    listOf(Resource("order/{t}/**"), Resource("http:path:/orders/**", caseSensitive = false)),
                    condition,
                    name = "read",
                ),
                Statement(Effect.DENY, listOf("order:delete")),
            ),
            attachable = true,
        )
        val operations = listOf(
            acme to Operation.PutPolicy(policy, ifMatch = "d1"),
            acme to Operation.RemovePolicy("old", ifMatch = "d2"),
            acme to Operation.PutBinding(
                Binding("ops", Subject.Role("ops"), "order", "*", "typed", Instant.parse("2027-01-01T00:00:00Z")),
            ),
            // The control plane names a principal's tenant in a tenant document's answers: the document's own, the same binding.
            acme to Operation.PutBinding(Binding("bob", Subject.Principal("bob", "acme"), "order", "team-a", "typed")),
            acme to Operation.RemoveBinding("old"),
            acme to Operation.PutRole(
                Role("night", Permissions.Of(listOf("warehouse.read")), "wms", "dock", "Night shift", "Watches"),
            ),
            acme to Operation.RemoveRole("old", ifMatch = "d3"),
            // A rule stays JSON: written as the control plane writes it back, defaults included.
            acme to Operation.PutRateLimit(
                RateLimitRule.of("""{"id":"r1","action":["order:read"],"permitsPerSecond":10.0,"windowSeconds":1}"""),
            ),
            acme to Operation.RemoveRateLimit("r0"),
            wms to Operation.PutCatalog(
                Catalog(
                    listOf(
                        CatalogGroup(
                            "Warehouse",
                            listOf(
                                CatalogPermission("warehouse.read", "Read", listOf("warehouse:read"), description = "Reads"),
                                CatalogPermission("warehouse.audit", "Audit"),
                            ),
                            spaced = true,
                            description = "The warehouse",
                        ),
                    ),
                ),
            ),
            wms to Operation.PutCatalog(null),
            wms to Operation.PutRole(Role("everything", Permissions.All, name = "Everything")),
        )
        operations.map { it.second::class }.toSet().size.assert().isEqualTo(Operation::class.sealedSubclasses.size - 1)
        operations.forEach { (key, operation) ->
            val once = throughControlPlane(key, written(key, operation))
            read(key, once).assert().describedAs("$operation").isEqualTo(operation)
            throughControlPlane(key, written(key, read(key, once))).assert().describedAs("$operation").isEqualTo(once)
        }
    }

    @Test
    fun `every change body of the wiki reads and is written back as the control plane reads it - CTL-76`() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
        val principal = Binding("bob", Subject.Principal("bob"), "order", "*", "typed")
        val read = read(acme, throughControlPlane(acme, written(acme, Operation.PutBinding(principal))))
        (read as Operation.PutBinding).binding.subject.assert().isEqualTo(Subject.Principal("bob", "acme"))
        val bodies = Files.walk(root.resolve("wiki/examples")).use { paths ->
            paths.filter { it.extension == "json" && it.parent.name == "changes" }.toList()
        }
        bodies.size.assert().isGreaterThan(5)
        bodies.forEach { path ->
            val body = CoSecJson.mapper.readTree(path.readText())
            val key = ChangeRequestCodec.documentKeyOf(body.get("document").asString())
            body.get("operations").values().forEach { node ->
                val again = written(key, read(key, node))
                throughControlPlane(key, again).assert().describedAs(path.toString())
                    .isEqualTo(throughControlPlane(key, node))
            }
        }
    }

    @Test
    fun `every change the control plane answers reads into a typed change - CTL-76`() {
        ChangeSamples.all.forEach { sample ->
            val answer = CoSecJson.mapper.writeValueAsString(ChangeAnswer.tree(sample, wholeOrigin = true))
            val change = runBlocking { ControlClient(Recorded { answer }.service).change(anyone, sample.id.value) }
            change.id.assert().isEqualTo(sample.id.value)
            change.key.assert().isEqualTo(sample.key)
            change.state.text.assert().isEqualTo(sample.state.name)
            change.state.isUnknown.assert().isFalse()
            change.kind.text.assert().isEqualTo(sample.kind.name)
            change.base.revision.assert().isEqualTo(sample.base.revision)
            change.base.digest.assert().isEqualTo(sample.base.digest?.toString())
            change.note.assert().isEqualTo(sample.note)
            change.author.principal.assert().isEqualTo(sample.author.principal.value)
            change.author.tenant.assert().isEqualTo(sample.author.tenant.value)
            change.operations.size.assert().isEqualTo(sample.operations.size)
            change.findings.map { "${it.check} ${it.path} ${it.status} ${it.severity}" }
                .assert().isEqualTo(sample.findings.map { "${it.check} ${it.path} ${it.status} ${it.severity.name}" })
            change.escalations.map { "${it.subject} ${it.reason} ${it.detail}" }
                .assert().isEqualTo(sample.escalations.map { "${it.subject} ${it.reason.name} ${it.detail}" })
            change.candidate.assert().isEqualTo(sample.candidate)
            change.publishedRevision.assert().isEqualTo(sample.publishedRevision)
            change.skipShadow.assert().isEqualTo(sample.skipShadow)
            change.shadow?.window.assert().isEqualTo(sample.shadow?.window)
            change.shadow?.closed?.counts.assert().isEqualTo(sample.shadow?.closed?.counts)
            change.approval?.covers.assert().isEqualTo(sample.approval?.covers)
            change.origin?.asserted.assert().isEqualTo(sample.origin?.asserted)
            change.origin?.method.assert().isEqualTo(sample.origin?.via?.method)
            change.origin?.peer.assert().isEqualTo(sample.origin?.peer)
            change.createdAt.assert().isEqualTo(sample.createdAt)
            change.updatedAt.assert().isEqualTo(sample.updatedAt)
        }
    }

    @Test
    fun `the typed calls read, propose, apply and publish over the in-process control plane - CTL-76`() {
        val fixture = ControlPlaneTest()
        runBlocking { fixture.memory.keepOpenChanges() }
        val client = ControlClient(LocalControlService(fixture.plane, IdentityGate()))
        val alice = ControlCaller.of(
            fixture.engine.security(
                ControlPlaneTest.ACME,
                "alice",
                roles = listOf("admin", "ops@team-a")
            ).securityContext,
        )
        val head = runBlocking { client.document(alice, acme) }
        head.revision.assert().isEqualTo(1L)
        head.content.policies.map { it.id }.assert().contains("orders")

        val typed = Policy("typed", listOf(Statement(Effect.ALLOW, listOf("order:read"))))
        val published = runBlocking {
            client.proposeAndPublishWithoutShadow(alice, acme, listOf(Operation.PutPolicy(typed)), "typed", "projected")
        }
        published.state.assert().isEqualTo(ChangeState.PUBLISHED)
        published.skipShadow.assert().isEqualTo("projected")
        published.publishedRevision.assert().isEqualTo(2L)
        val after = runBlocking { client.document(alice, acme) }
        after.revision.assert().isEqualTo(2L)
        after.content.policies.single { it.id == "typed" }.assert().isEqualTo(typed)

        val more = Policy("more", listOf(Statement(Effect.ALLOW, listOf("order:list"))))
        val applied = runBlocking {
            client.apply(
                alice,
                ApplyRequest(acme, 2, DocumentContent(policies = listOf(more)), ApplyMode.MERGE, "more")
            )
        }
        applied.state.assert().isEqualTo(ChangeState.CHECKED)
        applied.operations.assert().containsExactly(Operation.PutPolicy(more))
        val open = runBlocking { client.changes(alice, document = acme) }
        open.items.map { it.id }.assert().contains(applied.id)
        runBlocking { client.abandon(alice, applied.id) }.state.assert().isEqualTo(ChangeState.ABANDONED)

        // A proposal on a revision that is no longer the head: a refusal that says to read again (CTL-80).
        val stale = assertThrownBy<ControlRefusedException> {
            runBlocking { client.propose(alice, ChangeRequest(acme, 1, listOf(Operation.PutPolicy(more)), "stale")) }
        }.actual()
        stale.status.assert().isEqualTo(409)
        stale.conflict.assert().isEqualTo(ConflictKind.BASE_MOVED)
    }

    /** A control plane over the in-process implementation, and a caller of tenant acme with [roles]. */
    private class Plane {
        val fixture = ControlPlaneTest()
        val client = ControlClient(LocalControlService(fixture.plane, IdentityGate()))

        init {
            runBlocking { fixture.memory.keepOpenChanges() }
        }

        fun caller(name: String) = ControlCaller.of(
            fixture.engine.security(ControlPlaneTest.ACME, name, roles = listOf("admin", "ops@team-a")).securityContext,
        )

        /** A change of [caller]'s staged without a window and left there, as a writer that stopped halfway leaves it. */
        fun leftHalfway(caller: ControlCaller, id: String): String = runBlocking {
            val policy = Policy(id, listOf(Statement(Effect.ALLOW, listOf("order:read"))))
            val proposed = client.propose(
                caller,
                ChangeRequest(SnapshotKey.Tenant("acme"), 1, listOf(Operation.PutPolicy(policy)))
            )
            client.skipShadow(caller, proposed.id, "halfway").state.assert().isEqualTo(ChangeState.READY)
            proposed.id
        }
    }

    @Test
    fun `a write takes the slot a change of its own author left halfway - CTL-80`() {
        val plane = Plane()
        val alice = plane.caller("alice")
        val halfway = plane.leftHalfway(alice, "halfway")
        val typed = Operation.PutPolicy(Policy("typed", listOf(Statement(Effect.ALLOW, listOf("order:list")))))
        val published = runBlocking {
            plane.client.proposeAndPublishWithoutShadow(alice, acme, listOf(typed), "typed", "projected")
        }
        published.state.assert().isEqualTo(ChangeState.PUBLISHED)
        runBlocking { plane.client.change(alice, halfway) }.state.assert().isEqualTo(ChangeState.ABANDONED)
    }

    @Test
    fun `a write waits for a slot another author holds, and abandons only its own change - CTL-80`() {
        val plane = Plane()
        val alice = plane.caller("alice")
        val bob = plane.caller("bob")
        val held = plane.leftHalfway(bob, "bobs")
        val typed = Operation.PutPolicy(Policy("typed", listOf(Statement(Effect.ALLOW, listOf("order:list")))))
        val refused = assertThrownBy<ControlRefusedException> {
            runBlocking {
                plane.client.proposeAndPublishWithoutShadow(
                    alice,
                    acme,
                    listOf(typed),
                    "typed",
                    "projected",
                    Duration.ofMillis(200),
                )
            }
        }.actual()
        refused.conflict.assert().isEqualTo(ConflictKind.SLOT_HELD)
        refused.heldBy.assert().isEqualTo(held)
        runBlocking { plane.client.change(bob, held) }.state.assert().isEqualTo(ChangeState.READY)
        // What alice's write proposed is abandoned: bob's is the one open change of the document.
        runBlocking { plane.client.changes(alice, document = acme) }.items.map { it.id }.assert().containsExactly(held)
    }
}
