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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.engine.flat.ViewVersion
import me.ahoo.cosec.membership.MembershipClaims
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.authn.AuthenticatedPrincipal
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.Actor
import me.ahoo.cosec.snapshot.document.PolicyDefinition
import me.ahoo.cosec.snapshot.document.PolicyDocumentCompiler
import me.ahoo.cosec.snapshot.document.ProvenanceKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * RFC 0007 §9.2 (TNT-3) and the control-plane form of P12: a tenant's principal changes only its own tenant's
 * document, whatever the policies say, and nothing it publishes there changes a decision for another tenant's
 * principals. The authorizer here permits everything, so only the hard rule stands.
 */
class ControlTenantIsolationTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)
    private val ids = AtomicInteger()
    private val changes = Changes(store, revisions, {
        ChangeId("c${ids.incrementAndGet()}")
    }, PERMIT_ALL, clock, ChangeOptions(shadow = IMMEDIATE_WINDOW))

    private val acme = SnapshotKey.Tenant("acme")
    private val globex = SnapshotKey.Tenant("globex")
    private val gil = Operator(Actor(TenantId("globex"), PrincipalId("gil")), NO_MEMBERSHIPS)
    private val compiler = PolicyDocumentCompiler()

    init {
        runBlocking {
            val read = PolicyDefinition(
                PolicyId("read"),
                false,
                null,
                listOf(
                    StatementSource(
                        "",
                        Effect.ALLOW,
                        listOf("http:GET"),
                        listOf(ResourceSource("http:path:/orders/**"))
                    )
                )
            )
            val delete = PolicyDefinition(
                PolicyId("no-delete"),
                false,
                null,
                listOf(StatementSource("", Effect.DENY, listOf("http:DELETE")))
            )
            revisions.publish(
                acme,
                null,
                StoreFixtures.candidate(
                    acme,
                    listOf(read, delete),
                    listOf(
                        Binding(
                            BindingId("read"),
                            SubjectRef.Anyone,
                            BindingScope(Match.Any, Match.Any),
                            PolicyId("read")
                        ),
                        Binding(
                            BindingId("no-delete"),
                            SubjectRef.Role(TenantId("acme"), RoleId("editor")),
                            BindingScope(Match.Any, Match.Any),
                            PolicyId("no-delete")
                        ),
                    ),
                ),
                Publication("bootstrap", ProvenanceKind.BOOTSTRAP, ALICE),
            )
        }
    }

    private suspend fun refused(block: suspend () -> Unit): Int = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e.status
    }

    @Test
    fun `another tenant's principal is refused every step on this tenant's change`() = runBlocking<Unit> {
        val put = listOf(Operation.PutPolicy(allow("x")))
        refused { changes.propose(gil, acme, 1, put, "theirs") }.assert().isEqualTo(403)
        refused { changes.propose(gil, SnapshotKey.Platform, 0, put, "platform") }.assert().isEqualTo(403)
        refused { changes.propose(gil, SnapshotKey.App("order"), 0, put, "app") }.assert().isEqualTo(403)
        refused { changes.rollback(gil, acme, 1, "back") }.assert().isEqualTo(403)
        refused {
            changes.apply(
                gil,
                acme,
                StoreFixtures.candidate(acme),
                ApplyMode.REPLACE,
                "empty"
            )
        }.assert().isEqualTo(403)
        val change = changes.propose(ALICE_OP, acme, 1, put, "mine")
        refused { changes.stage(gil, change.id) }.assert().isEqualTo(403)
        refused { changes.skipShadow(gil, change.id, "hurry") }.assert().isEqualTo(403)
        refused { changes.abandon(gil, change.id) }.assert().isEqualTo(403)
        refused { changes.rebase(gil, change.id) }.assert().isEqualTo(403)
        changes.skipShadow(ALICE_OP, change.id, "test")
        refused { changes.publish(gil, change.id) }.assert().isEqualTo(403)
        refused { changes.reject(gil, change.id, "no") }.assert().isIn(403, 409)
        revisions.head(acme)!!.revision.assert().isEqualTo(1)
    }

    /** P12, through the control plane: a tenant administrator's changes to its own document never reach another tenant. */
    @Test
    fun `nothing a tenant publishes changes a decision for another tenant's principals`() = runBlocking<Unit> {
        val before = decisions()
        val random = Random(1076)
        repeat(ROUNDS) { round ->
            val head = revisions.head(globex)?.revision ?: 0
            val operations = operations(random, round)
            val change = changes.propose(gil, globex, head, operations, "round $round")
            if (change.state == ChangeState.CHECKED) {
                changes.skipShadow(gil, change.id, "test")
                changes.publish(gil, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
            } else {
                changes.abandon(gil, change.id)
            }
            refused { changes.propose(gil, acme, 1, operations, "round $round, theirs") }.assert().isEqualTo(403)
            decisions().assert().describedAs("round $round").isEqualTo(before)
        }
        (revisions.head(globex)?.revision ?: 0).assert().isGreaterThan(ROUNDS / 2L)
    }

    private fun operations(random: Random, round: Int): List<Operation> = List(random.nextInt(1, 4)) { i ->
        val policy = "p${random.nextInt(4)}"
        when (random.nextInt(3)) {
            0 -> Operation.PutPolicy(
                PolicyDefinition(
                    PolicyId(policy),
                    random.nextBoolean(),
                    null,
                    listOf(
                        StatementSource(
                            "",
                            if (random.nextBoolean()) Effect.ALLOW else Effect.DENY,
                            listOf(ACTIONS.random(random)),
                            listOf(ResourceSource(PATHS.random(random)))
                        )
                    ),
                ),
            )
            1 -> Operation.PutBinding(
                Binding(
                    BindingId("b$round-$i"),
                    listOf(
                        SubjectRef.Anyone,
                        SubjectRef.Role(TenantId("globex"), RoleId("editor")),
                        SubjectRef.Principal(TenantId("globex"), PrincipalId("bob"))
                    ).random(random),
                    BindingScope(Match.Any, Match.Any),
                    PolicyId(policy),
                ),
            )
            else -> Operation.PutPolicy(allow(policy, action = ACTIONS.random(random)))
        }
    }

    /** acme's principals' answers to a set of requests, through the pipeline and engine, on the current heads. */
    private fun decisions(): List<Int> = REQUESTS.map { (principal, method, path) ->
        val pipeline = Pipeline(
            PipelineComponents(
                authenticator = Authenticator {
                    AuthnResult.Authenticated(
                        AuthenticatedPrincipal(
                            PrincipalId(principal),
                            TenantId("acme"),
                            MembershipClaims.parse(listOf("editor"), listOf("p0", "p1"))
                        ),
                        "token"
                    )
                },
                snapshots = SuspendingSnapshotSource(HeadViews()),
            )
        )
        runBlocking { pipeline.evaluate(facts(method, path)).status }
    }

    private inner class HeadViews : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView? = null

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(
            runBlocking {
                val tenant = key.tenant?.let { revisions.head(SnapshotKey.Tenant(it.value)) }
                SnapshotView(
                    CompiledDocument.platform(emptyList(), emptyList()),
                    null,
                    key.tenant?.let { id ->
                        tenant?.document?.let {
                            compiler.compile(it)
                        } ?: CompiledDocument.tenant(id, emptyList(), emptyList())
                    },
                    version = ViewVersion(0, null, tenant?.revision ?: 0),
                )
            },
        )
    }

    private companion object {
        const val ROUNDS = 25
        val ACTIONS = listOf("http:GET", "http:DELETE", "http:*", "*")
        val PATHS = listOf("http:path:/orders/**", "http:path:/**", "http:path:/admin/**")
        val REQUESTS = listOf(
            Triple("bob", "GET", "/orders/1"),
            Triple("bob", "DELETE", "/orders/1"),
            Triple("bob", "GET", "/admin/1"),
            Triple("eve", "POST", "/orders/1"),
        )

        fun facts(method: String, path: String) = HttpFacts(
            method = method,
            rawPath = path,
            rawQuery = null,
            headers = Headers.of(emptyMap()),
            cookies = Cookies.of(emptyMap()),
            remoteAddress = "10.0.0.1",
            host = HostScope(null),
        )
    }
}
