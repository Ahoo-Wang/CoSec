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

package me.ahoo.cosec.cache.control

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cosec.cache.snapshot.RedisTarget
import me.ahoo.cosec.cache.snapshot.SnapshotNode
import me.ahoo.cosec.cache.snapshot.newKeyPrefix
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.ShadowOptions
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.spi.membership.MembershipClaims
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
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * The control plane end to end on real Redis against its budget (RFC 0007 §14.2): `propose` and `publish` (without
 * the shadow window and approval) of a change to a tenant document of about 1 MB, through `CoCacheControlStore`. The
 * numbers are printed for `docs/perf/rfc-0007-control/`; the assertion is the budget's, with room for a loaded CI host.
 */
class ControlPlaneLatencyTest {
    private val backend: CoherenceBackend = RedisTarget.REDIS.backend()
    private val keyPrefix = newKeyPrefix()
    private val node = SnapshotNode(backend, keyPrefix)
    private val store = CoCacheControlStore.create(node.runtime, node.cache, keyPrefix)
    private val revisions = Revisions(store)
    private val ids = AtomicInteger()
    private val author =
        Operator(Actor(ACME, PrincipalId("alice")), MembershipClaims.parse(listOf("ops@team-a"), emptyList()))
    private val changes = Changes(
        store,
        revisions,
        { ChangeId("lat${ids.incrementAndGet()}") },
        // Every action but cosec:approve: the author is analyzed as a tenant administrator is.
        ControlAuthorizer { _, action, _ -> if (action == ControlActions.APPROVE) ControlDecision.DENY else ControlDecision.PERMIT },
        options = ChangeOptions(shadow = ShadowOptions(Duration.ZERO, 0, Duration.ZERO)),
    )

    @AfterEach
    fun close() {
        node.close()
        backend.close()
    }

    /** A tenant document of [POLICIES] policies, about 1 MB, with the right the author grants from. */
    private fun document(): PolicyDocument {
        val orders =
            PolicyDefinition(
                PolicyId("orders"),
                false,
                null,
                listOf(StatementSource("", Effect.ALLOW, listOf("order:*"), listOf(ResourceSource("order/**"))))
            )
        val filler = (0 until POLICIES).map { i ->
            PolicyDefinition(
                PolicyId("p$i"),
                false,
                null,
                listOf(
                    StatementSource(
                        "s$i",
                        Effect.ALLOW,
                        listOf("svc$i:read", "svc$i:list"),
                        listOf(ResourceSource("svc$i/{id}/**"))
                    )
                )
            )
        }
        return PolicyDocument(
            DocumentKind.TENANT,
            ACME.value,
            1,
            Instant.EPOCH,
            listOf(orders) + filler,
            listOf(
                Binding(
                    BindingId("ops"),
                    SubjectRef.Role(ACME, RoleId("ops")),
                    BindingScope(Match.Exactly(ORDER), Match.Any),
                    PolicyId("orders")
                )
            ),
        )
    }

    /** A viewer granted reading one order in team-a, where the author holds orders: contained, no approval. */
    private fun grant(round: Int): List<Operation> {
        return listOf(
            Operation.PutPolicy(
                PolicyDefinition(
                    PolicyId("viewer-read"),
                    false,
                    null,
                    listOf(
                        StatementSource(
                            "r$round",
                            Effect.ALLOW,
                            listOf("order:read"),
                            listOf(ResourceSource("order/{id}"))
                        )
                    )
                )
            ),
            Operation.PutBinding(
                Binding(
                    BindingId("viewers"),
                    SubjectRef.Role(ACME, RoleId("viewer")),
                    BindingScope(Match.Exactly(ORDER), Match.Exactly(SpaceId("team-a"))),
                    PolicyId("viewer-read")
                )
            ),
        )
    }

    @Test
    fun `propose and publish a change to a 1 MB tenant document within budget`() = runBlocking<Unit> {
        val document = document()
        val bytes = PolicyDocumentCodec.encode(document).size
        revisions.publish(KEY, null, document, Publication("bootstrap", ProvenanceKind.BOOTSTRAP, author.actor))
        val proposals = ArrayList<Long>()
        val publishes = ArrayList<Long>()
        repeat(ROUNDS) { round ->
            val operations = grant(round)
            val start = System.nanoTime()
            val change = changes.propose(author, KEY, revisions.head(KEY)!!.revision, operations, "latency")
            val proposed = System.nanoTime()
            changes.skipShadow(author, change.id, "latency")
            changes.publish(author, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
            val published = System.nanoTime()
            if (round >= WARMUP) {
                proposals += (proposed - start) / NANOS_PER_MILLI
                publishes += (published - start) / NANOS_PER_MILLI
            }
        }
        val propose = percentiles(proposals)
        val publish = percentiles(publishes)
        println(
            "control plane on Redis, tenant document $bytes bytes: propose p50/p99 ${propose.first}/${propose.second} ms, publish p50/p99 ${publish.first}/${publish.second} ms"
        )
        bytes.assert().isGreaterThan(MIN_BYTES)
        // RFC 0007 §14.2: publish end to end ≤ 1 s at 1 MB; three times that on a loaded CI host is still within the 8 MB budget.
        publish.second.assert().isLessThan(BUDGET_MILLIS)
    }

    private fun percentiles(values: List<Long>): Pair<Long, Long> {
        val sorted = values.sorted()
        return sorted[sorted.size / 2] to sorted[(sorted.size * P99 / 100).coerceAtMost(sorted.size - 1)]
    }

    private companion object {
        val ACME = TenantId("acme")
        val ORDER = AppId("order")
        val KEY: SnapshotKey = SnapshotKey.Tenant("acme")
        const val POLICIES = 7_000
        const val ROUNDS = 25
        const val WARMUP = 5
        const val P99 = 99
        const val MIN_BYTES = 900_000
        const val BUDGET_MILLIS = 3_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
