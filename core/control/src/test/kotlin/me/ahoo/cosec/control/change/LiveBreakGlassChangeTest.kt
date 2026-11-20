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
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.EngineAuthority
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.lifecycle.BreakGlass
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.engine.BuiltinPolicies
import me.ahoo.cosec.engine.ResourceSource
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * `APR-4`: a changed break-glass binding needs a second person as a new one does, whoever proposes it. Its requester
 * lengthens a live one: the change carries the break-glass escalation and waits for an approval. A mutant that asked a
 * second person only for a new binding survived [BreakGlassControlTest], which only creates one.
 */
class LiveBreakGlassChangeTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)
    private val engine = EngineAuthority(revisions)
    private val ids = AtomicInteger()

    /** The REQUIRED change listener break-glass needs. */
    private val audit = object : ChangeListener {
        override val name: String = "audit"
        override val delivery: Delivery = Delivery.REQUIRED

        override suspend fun onChange(event: ChangeEvent) = Unit
    }
    private val listeners = ChangeListeners(listOf(audit))
    private val changes = Changes(
        store,
        revisions,
        { ChangeId("c${ids.incrementAndGet()}") },
        engine.authorizer,
        clock,
        ChangeOptions(shadow = IMMEDIATE_WINDOW),
        listeners = listeners,
        revocations = RevocationReader.NONE,
        lockout = LockoutCheck.UNCHECKED,
    )
    private val breakGlass = BreakGlass(changes, revisions)
    private val plato = engine.operator(TenantId.PLATFORM, "plato")
    private val pia = engine.operator(TenantId.PLATFORM, "pia")
    private val rita = engine.operator(TenantId.PLATFORM, "rita")

    init {
        runBlocking {
            // rita may request and revoke break-glass, and nothing else; pia approves.
            val requester = PolicyDefinition(
                PolicyId("break-glass-requester"),
                false,
                null,
                listOf(
                    StatementSource(
                        "",
                        Effect.ALLOW,
                        listOf("cosec:breakglass", "cosec:publish"),
                        listOf(ResourceSource("doc/platform"))
                    )
                ),
            )
            revisions.publish(
                SnapshotKey.Platform,
                null,
                StoreFixtures.candidate(
                    SnapshotKey.Platform,
                    listOf(requester),
                    listOf(
                        control("plato", BuiltinPolicies.PLATFORM_ADMIN),
                        control("pia", BuiltinPolicies.PLATFORM_ADMIN),
                        control("rita", PolicyId("break-glass-requester")),
                    ),
                ),
                Publication("bootstrap", ProvenanceKind.BOOTSTRAP, plato.actor),
            )
        }
    }

    @AfterEach
    fun close() {
        listeners.cancel()
    }

    private fun control(principal: String, policy: PolicyId) = Binding(
        BindingId(principal),
        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId(principal)),
        BindingScope(Match.Exactly(PipelineControlAuthorizer.CONTROL_APP), Match.Exactly(SpaceId.DEFAULT)),
        policy,
    )

    private fun inHours(hours: Long) = clock.now.plus(Duration.ofHours(hours))

    @Test
    fun `changing a live binding needs a second person too`() = runBlocking<Unit> {
        val requested = breakGlass.request(rita, PrincipalId("rita"), inHours(1), "incident 42")
        changes.approve(pia, requested.id, requested.candidate!!)
        changes.publish(rita, requested.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        val live = revisions.head(SnapshotKey.Platform)!!.document.bindings.single { it.breakGlass }
        val longer =
            Binding(live.id, live.subject, live.scope, live.policy, inHours(2).toEpochMilli(), breakGlass = true)
        val change = changes.propose(
            rita,
            SnapshotKey.Platform,
            revisions.head(SnapshotKey.Platform)!!.revision,
            listOf(Operation.PutBinding(longer)),
            "incident 42, longer",
            kind = ProvenanceKind.BREAK_GLASS,
        )
        change.state.assert().describedAs("${change.findings}").isEqualTo(ChangeState.CHECKED)
        change.escalations.map { it.subject }.assert().contains("break-glass binding ${live.id.value}")
        // Break-glass has no shadow window: staging it goes straight to the second person.
        changes.stage(rita, change.id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
    }
}
