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

package me.ahoo.cosec.control.lifecycle

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.EngineAuthority
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.engine.BuiltinPolicies
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.ResourceSource
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.engine.StatementSource
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
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.measureTimedValue

/**
 * R1B-4: what a tenant document of n policies (2n statements, n bindings, a tenth of them to `Anyone`) costs the
 * control plane and a host, to choose the budgets' defaults. Not a check: run it by hand, niced, with
 * `COSEC_MEASURE_BUDGETS=true ./gradlew :control:test --tests '*DocumentBudgetMeasurement'`.
 *
 * Authorization reads the heads through views that compile each head once per revision, as a host's snapshot source
 * does (the tests' `HeadViews` compiles on every check, which no host does).
 */
@EnabledIfEnvironmentVariable(named = "COSEC_MEASURE_BUDGETS", matches = "true")
class DocumentBudgetMeasurement {
    private val acme = SnapshotKey.Tenant("acme")

    private fun policies(n: Int, prefix: String = "p") = (0 until n).map { i ->
        PolicyDefinition(
            PolicyId("$prefix$i"),
            false,
            null,
            listOf(
                StatementSource("read", Effect.ALLOW, listOf("order:read"), listOf(ResourceSource("order/$i/**"))),
                StatementSource(
                    "no-cancel",
                    Effect.DENY,
                    listOf("order:cancel"),
                    listOf(ResourceSource("order/$i/**"))
                ),
            ),
        )
    }

    private fun bindings(n: Int, prefix: String = "p") = (0 until n).map { i ->
        Binding(
            BindingId("b-$prefix$i"),
            if (i % 10 == 0) SubjectRef.Anyone else SubjectRef.Role(TenantId("acme"), RoleId("r${i % 50}")),
            BindingScope(Match.Any, Match.Any),
            PolicyId("$prefix$i"),
        )
    }

    private fun operations(n: Int, prefix: String = "p"): List<Operation> =
        policies(n, prefix).map { Operation.PutPolicy(it) } + bindings(n, prefix).map { Operation.PutBinding(it) }

    /** The heads of [revisions], each compiled once per revision. */
    private class CachingViews(private val revisions: Revisions) : SnapshotViews {
        private val compiled = ConcurrentHashMap<Pair<SnapshotKey, Long>, CompiledDocument>()

        override fun peek(key: ViewKey): SnapshotView? = null

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> =
            CompletableFuture.completedFuture(runBlocking { view(key) })

        private suspend fun compiled(key: SnapshotKey, platform: PolicyDocument?): CompiledDocument? {
            val head = revisions.head(key) ?: return null
            return compiled.computeIfAbsent(key to head.revision) {
                PolicyDocumentCompiler.forPlatform(platform).compile(head.document)
            }
        }

        private suspend fun view(key: ViewKey): SnapshotView {
            val platform = revisions.head(SnapshotKey.Platform)?.document
            return SnapshotView(
                compiled(SnapshotKey.Platform, platform) ?: CompiledDocument.platform(emptyList(), emptyList()),
                key.app?.let { app ->
                    compiled(SnapshotKey.App(app.value), platform) ?: CompiledDocument.app(app, emptyList(), emptyList())
                },
                key.tenant?.let { tenant ->
                    compiled(SnapshotKey.Tenant(tenant.value), platform)
                        ?: CompiledDocument.tenant(tenant, emptyList(), emptyList())
                },
            )
        }
    }

    private class Plane {
        val clock = MutableClock()
        val store = InMemoryControlStore(clock)
        val revisions = Revisions(store, clock)
        val engine = EngineAuthority(revisions, views = CachingViews(revisions))
        val ids = AtomicInteger()
        val changes = Changes(
            store,
            revisions,
            { ChangeId("c${ids.incrementAndGet()}") },
            engine.authorizer,
            clock,
            // Above the default 8 MB (V2), which this document shape reaches at about 28k entries: the cost is the question.
            ChangeOptions(shadow = IMMEDIATE_WINDOW, maxDocumentBytes = 256 * 1024 * 1024),
            revocations = RevocationReader.NONE,
            lockout = LockoutCheck.UNCHECKED,
        )
        val plato = engine.operator(TenantId.PLATFORM, "plato")
        val alice = engine.operator(TenantId("acme"), "alice", roles = listOf("admin"))

        private fun control(id: String, subject: SubjectRef, policy: PolicyId) = Binding(
            BindingId(id),
            subject,
            BindingScope(Match.Exactly(PipelineControlAuthorizer.CONTROL_APP), Match.Exactly(SpaceId.DEFAULT)),
            policy,
        )

        suspend fun bootstrap(key: SnapshotKey, bindings: List<Binding>) {
            val document = StoreFixtures.candidate(key, emptyList(), bindings)
            revisions.publish(
                key,
                revisions.head(key),
                // The cost past the entry budgets (CTL-40, CTL-41) is the question, so the platform lifts them.
                if (key == SnapshotKey.Platform) document.copy(budgets = UNBOUNDED) else document,
                Publication("bootstrap", ProvenanceKind.BOOTSTRAP, plato.actor),
            )
        }

        init {
            runBlocking {
                bootstrap(
                    SnapshotKey.Platform,
                    listOf(
                        control(
                            "plato",
                            SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("plato")),
                            BuiltinPolicies.PLATFORM_ADMIN
                        )
                    ),
                )
                bootstrap(
                    SnapshotKey.Tenant("acme"),
                    listOf(
                        control(
                            "admins",
                            SubjectRef.Role(TenantId("acme"), RoleId("admin")),
                            BuiltinPolicies.TENANT_ADMIN
                        )
                    ),
                )
            }
        }
    }

    private companion object {
        val UNBOUNDED = me.ahoo.cosec.document.DocumentBudgets(
            maxPolicies = Int.MAX_VALUE,
            maxStatements = Int.MAX_VALUE,
            maxRoleBindings = Int.MAX_VALUE,
            maxAnyoneBindings = Int.MAX_VALUE,
            maxChangeOperations = Int.MAX_VALUE,
        )
    }

    private inline fun ms(block: () -> Any?): Long = measureTimedValue(block).duration.inWholeMilliseconds

    private fun row(n: Int): String = runBlocking {
        val candidate = StoreFixtures.candidate(acme, policies(n), bindings(n))
        val compiler = PolicyDocumentCompiler()
        val compile = ms { compiler.compile(candidate) }
        val bytes = me.ahoo.cosec.document.PolicyDocumentCodec.encode(candidate).size

        // The trust root proposes, skips the window and publishes alone: authorization of every target, checks, compile.
        val plane = Plane()
        val base = plane.revisions.head(acme)!!.revision
        val (proposed, propose) = measureTimedValue {
            plane.changes.propose(
                plane.plato,
                acme,
                base,
                operations(n),
                "n=$n"
            )
        }
        plane.changes.skipShadow(plane.plato, proposed.id, "measure")
        val publish = ms { plane.changes.publish(plane.plato, proposed.id) }

        // A change of n more entries, then another change moves the head: rebase checks it all again.
        val head = plane.revisions.head(acme)!!.revision
        val pending = plane.changes.propose(plane.plato, acme, head, operations(n, "q"), "pending")
        val small = plane.changes.propose(plane.plato, acme, head, operations(1, "s"), "small")
        plane.changes.skipShadow(plane.plato, small.id, "measure")
        plane.changes.publish(plane.plato, small.id)
        val rebase = ms { plane.changes.rebase(plane.plato, pending.id) }

        // A tenant administrator's own change: analysed for escalation, on a document that already holds n entries.
        val fresh = Plane()
        val analysed = ms {
            fresh.changes.propose(fresh.alice, acme, fresh.revisions.head(acme)!!.revision, operations(n), "alice")
        }
        "| $n | ${2 * n} | $n | ${bytes / 1024} KB | $compile | ${propose.inWholeMilliseconds} | $analysed | $rebase | $publish |" +
            " ${proposed.state} |"
    }

    @Test
    fun measure() {
        row(200) // warm-up
        println(
            "| policies | statements | bindings | stored | compile ms | propose (root) ms | propose (tenant admin) ms | " +
                "rebase ms | publish ms | state |\n|---|---|---|---|---|---|---|---|---|---|",
        )
        // Each row as soon as it is measured. The 100k row needs a test heap above Gradle's default 512 MB, in which it
        // runs out and hangs: run it with a larger one (4 GB was enough).
        for (n in listOf(1_000, 10_000, 30_000, 100_000)) {
            println(row(n))
            System.out.flush()
        }
    }
}
