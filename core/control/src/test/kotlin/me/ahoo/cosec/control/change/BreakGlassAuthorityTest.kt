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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.authz.EngineAuthority
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.StatementSource
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
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * RFC 0007 §8: `cosec:breakglass` is what requesting break-glass, publishing it once approved and revoking it take, and
 * it stands in for `cosec:publish` on nothing else. A caller who may not request it learns nothing of the bindings there
 * are.
 */
class BreakGlassAuthorityTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private val revisions = Revisions(memory, clock)
    private val engine = EngineAuthority(revisions)
    private val ids = AtomicInteger()

    private val listeners = ChangeListeners(
        listOf(
            object : ChangeListener {
                override val name: String = "audit"
                override val delivery: Delivery = Delivery.REQUIRED

                override suspend fun onChange(event: ChangeEvent) = Unit
            }
        ),
    )
    private val changes = Changes(
        memory,
        revisions,
        { ChangeId("c${ids.incrementAndGet()}") },
        engine.authorizer,
        clock,
        ChangeOptions(shadow = IMMEDIATE_WINDOW),
        listeners = listeners,
    )
    private val breakGlass = BreakGlass(changes, revisions)

    private val plato = engine.operator(TenantId.PLATFORM, "plato")
    private val pia = engine.operator(TenantId.PLATFORM, "pia")

    /** Holds cosec:breakglass on the platform document and nothing else. */
    private val bianca = engine.operator(TenantId.PLATFORM, "bianca")

    /** Holds cosec:breakglass and cosec:read, so that it sees an ordinary change it holds no cosec:publish on. */
    private val vera = engine.operator(TenantId.PLATFORM, "vera")
    private val alice = engine.operator(TenantId("acme"), "alice")

    private fun holding(id: String, vararg actions: String) = PolicyDefinition(
        PolicyId(id),
        false,
        null,
        listOf(StatementSource("", Effect.ALLOW, actions.toList(), listOf(ResourceSource("doc/platform")))),
    )

    private fun control(principal: String, policy: PolicyId) = Binding(
        BindingId(principal),
        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId(principal)),
        BindingScope(Match.Exactly(PipelineControlAuthorizer.CONTROL_APP), Match.Exactly(SpaceId.DEFAULT)),
        policy,
    )

    init {
        runBlocking {
            revisions.publish(
                SnapshotKey.Platform,
                null,
                StoreFixtures.candidate(
                    SnapshotKey.Platform,
                    listOf(
                        holding("operator", "cosec:breakglass"),
                        holding("reader", "cosec:breakglass", "cosec:read")
                    ),
                    listOf(
                        control("plato", BuiltinPolicies.PLATFORM_ADMIN),
                        control("pia", BuiltinPolicies.PLATFORM_ADMIN),
                        control("bianca", PolicyId("operator")),
                        control("vera", PolicyId("reader")),
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

    private fun inHours(hours: Long) = clock.now.plus(Duration.ofHours(hours))

    private suspend fun refused(block: suspend () -> Unit): Int = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e.status
    }

    private suspend fun glass(): Binding? =
        revisions.head(SnapshotKey.Platform)!!.document.bindings.singleOrNull { it.breakGlass }

    private suspend fun published(expiresAt: java.time.Instant): ChangeRequest {
        val requested = breakGlass.request(bianca, PrincipalId("bianca"), expiresAt, "incident")
        requested.state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        changes.approve(pia, requested.id, requested.candidate!!)
        return changes.publish(bianca, requested.id)
    }

    @Test
    fun `cosec breakglass alone requests it, publishes what was approved and revokes it`() = runBlocking<Unit> {
        published(inHours(2)).state.assert().isEqualTo(ChangeState.PUBLISHED)
        breakGlass.revoke(bianca, glass()!!.id, "done").state.assert().isEqualTo(ChangeState.PUBLISHED)
        glass().assert().isNull()
    }

    @Test
    fun `cosec breakglass does not stand in for cosec publish on any other change`() = runBlocking<Unit> {
        val change = changes.propose(
            plato,
            SnapshotKey.Platform,
            1,
            listOf(Operation.PutPolicy(allow("x"))),
            "ordinary"
        )
        refused { changes.stage(vera, change.id) }.assert().isEqualTo(403)
        changes.skipShadow(plato, change.id, "test").state.assert().isEqualTo(ChangeState.READY)
        refused { changes.publish(vera, change.id) }.assert().isEqualTo(403)
    }

    @Test
    fun `a refused request tells nothing of the bindings there are`() = runBlocking<Unit> {
        val expiresAt = inHours(2)
        published(expiresAt)
        val nobody = engine.operator(TenantId.PLATFORM, "nobody")
        for (caller in listOf(alice, nobody)) {
            refused { breakGlass.request(caller, PrincipalId("bianca"), expiresAt, "probe") }.assert().isEqualTo(403)
            refused {
                breakGlass.request(caller, PrincipalId("bianca"), expiresAt.plusMillis(1), "probe")
            }.assert().isEqualTo(403)
        }
    }
}
