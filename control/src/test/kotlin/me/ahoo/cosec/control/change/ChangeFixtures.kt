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

import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.Actor
import me.ahoo.cosec.snapshot.document.PolicyDefinition
import java.util.concurrent.atomic.AtomicInteger

/** A control plane over an in-memory store and a clock the test moves. */
class ChangeHarness(
    val clock: MutableClock = MutableClock(),
    val memory: InMemoryControlStore = InMemoryControlStore(clock),
    store: ControlStore = memory,
    options: ChangeOptions = ChangeOptions(shadow = IMMEDIATE_WINDOW),
) {
    private val counter = AtomicInteger()
    val revisions = Revisions(store, clock)
    val changes = Changes(store, revisions, { ChangeId("c${counter.incrementAndGet()}") }, PERMIT_ALL, clock, options)
}

val ACME: SnapshotKey = StoreFixtures.ACME

/** A shadow window that may close as soon as it is looked at: for tests of the flow around it. */
val IMMEDIATE_WINDOW = ShadowOptions(java.time.Duration.ZERO, 0, java.time.Duration.ZERO)
val ALICE = StoreFixtures.ALICE

val NO_MEMBERSHIPS = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())

/** Permits every control-plane action: for tests of the change flow, where only the hard rule stands. */
val PERMIT_ALL = ControlAuthorizer { _, _, _ -> ControlDecision.PERMIT }

/** Alice of acme, as she proposes and publishes acme's document. */
val ALICE_OP = Operator(ALICE, NO_MEMBERSHIPS)

/** A principal of the platform tenant: the only one the hard rule lets write the platform and app documents. */
val PLATO = Operator(Actor(TenantId.PLATFORM, PrincipalId("plato")), NO_MEMBERSHIPS)

/** The operator the hard rule lets act on [key]. */
fun operatorFor(key: SnapshotKey): Operator = if (key is SnapshotKey.Tenant) ALICE_OP else PLATO

fun allow(id: String, action: String = "order:read", resource: String? = null, condition: Condition? = null) =
    PolicyDefinition(
        PolicyId(id),
        attachable = false,
        condition = null,
        statements = listOf(
            StatementSource("", Effect.ALLOW, listOf(action), listOfNotNull(resource?.let(::ResourceSource)), condition)
        ),
    )

/** A policy whose condition reads an attribute outside the catalog: it never compiles. */
fun broken(id: String) = allow(id, condition = Condition.Eq("no.such.attribute", Literal.Str("x")))

fun bind(id: String, policy: String, app: String? = null) = Binding(
    BindingId(id),
    SubjectRef.Role(null, RoleId("editor")),
    BindingScope(app?.let { Match.Exactly(AppId(it)) } ?: Match.Any, Match.Any),
    PolicyId(policy),
)
