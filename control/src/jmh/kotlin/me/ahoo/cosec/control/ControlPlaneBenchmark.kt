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

package me.ahoo.cosec.control

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.ShadowOptions
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.membership.MembershipClaims
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.Actor
import me.ahoo.cosec.snapshot.document.PolicyDefinition
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import me.ahoo.cosec.snapshot.document.ProvenanceKind
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The control plane against its budget (RFC 0007 §14.2), in memory: `propose` (authorization of every target,
 * validation, the strict compile of the candidate with the platform, and the grant analysis of a binding the author
 * holds the rights for) and `publish` end to end (propose, skip the window, publish: checks again, record, head and
 * index). [policies] sizes the tenant document: 100 policies is a few kilobytes, 7,000 about 1 MB (RFC 0003 Q14).
 *
 * The authorizer permits every action but `cosec:approve`, so the author is analyzed like a tenant administrator, not
 * trusted as a platform one. The engine's own checks are measured by the engine's benchmarks.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class ControlPlaneBenchmark {
    @Param("100", "7000")
    var policies: Int = 0

    private lateinit var store: InMemoryControlStore
    private lateinit var revisions: Revisions
    private lateinit var changes: Changes
    private val ids = AtomicLong()
    private val author = Operator(Actor(ACME, PrincipalId("alice")), MembershipClaims.parse(listOf("ops@team-a"), emptyList()))

    @Setup(Level.Trial)
    fun setUp() = runBlocking {
        store = InMemoryControlStore()
        revisions = Revisions(store)
        val authorizer = ControlAuthorizer { _, action, _ -> if (action == ControlActions.APPROVE) ControlDecision.DENY else ControlDecision.PERMIT }
        changes = Changes(
            store,
            revisions,
            { ChangeId("c${ids.incrementAndGet()}") },
            authorizer,
            options = ChangeOptions(shadow = ShadowOptions(Duration.ZERO, 0, Duration.ZERO)),
        )
        val orders = PolicyDefinition(PolicyId("orders"), false, null, listOf(StatementSource("", Effect.ALLOW, listOf("order:*"), listOf(ResourceSource("order/**")))))
        val filler = (0 until policies).map { i ->
            PolicyDefinition(
                PolicyId("p$i"),
                false,
                null,
                listOf(StatementSource("s$i", Effect.ALLOW, listOf("svc$i:read", "svc$i:list"), listOf(ResourceSource("svc$i/{id}/**")))),
            )
        }
        val document = PolicyDocument(
            DocumentKind.TENANT,
            ACME.value,
            1,
            Instant.EPOCH,
            listOf(orders) + filler,
            listOf(Binding(BindingId("ops"), SubjectRef.Role(ACME, RoleId("ops")), BindingScope(Match.Exactly(ORDER), Match.Any), PolicyId("orders"))),
        )
        revisions.publish(KEY, null, document, Publication("bootstrap", ProvenanceKind.BOOTSTRAP, author.actor))
        println("tenant document: ${PolicyDocumentCodec.encode(document).size} bytes, ${document.policies.size} policies")
    }

    /** A viewer granted reading one order in team-a, where the author holds orders: contained, no approval. */
    private fun grant(): List<Operation> = listOf(
        Operation.PutPolicy(PolicyDefinition(PolicyId("viewer-read"), false, null, listOf(StatementSource("", Effect.ALLOW, listOf("order:read"), listOf(ResourceSource("order/{id}")))))),
        Operation.PutBinding(Binding(BindingId("viewers"), SubjectRef.Role(ACME, RoleId("viewer")), BindingScope(Match.Exactly(ORDER), Match.Exactly(SpaceId("team-a"))), PolicyId("viewer-read"))),
    )

    @Benchmark
    fun propose(): ChangeRequest = runBlocking {
        val change = changes.propose(author, KEY, revisions.head(KEY)!!.revision, grant(), "bench")
        check(change.escalations.isEmpty()) { "The grant is contained: $change" }
        changes.abandon(author, change.id)
    }

    @Benchmark
    fun publish(): ChangeRequest = runBlocking {
        val change = changes.propose(author, KEY, revisions.head(KEY)!!.revision, grant(), "bench")
        changes.skipShadow(author, change.id, "bench")
        changes.publish(author, change.id)
    }

    private companion object {
        val ACME = TenantId("acme")
        val ORDER = AppId("order")
        val KEY: SnapshotKey = SnapshotKey.Tenant("acme")
    }
}
