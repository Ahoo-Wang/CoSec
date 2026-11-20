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

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlPlaneTest.Companion.ORDER
import me.ahoo.cosec.control.api.ControlPlaneTest.Companion.TENANT
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.engine.ResourceSource
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `CTL-51`: `simulate` and `explain` name a statement, in the decision and in the trace, only when the caller may read the
 * document that defines its policy. A tenant's binding may point to a platform policy or to an app's permission policy, so
 * the document the *binding* lives in, which a tenant administrator reads, says nothing about who may read the statement.
 */
class DecisionQueriesTest {
    private val harness = ControlPlaneTest()
    private val plane = harness.plane
    private val app = SnapshotKey.App("order")

    private fun policy(id: String, name: String, action: String) = PolicyDefinition(
        PolicyId(id),
        false,
        null,
        listOf(StatementSource(name, Effect.ALLOW, listOf(action), listOf(ResourceSource("order/**")))),
    )

    /** The tenant binds a platform policy to everyone, as the target scenario's `base-access`, and an app's policy. */
    private fun fixtures() = runBlocking {
        val platform = plane.read(harness.plato, SnapshotKey.Platform)
        harness.bootstrap(
            SnapshotKey.Platform,
            platform.policies + policy("platform-base", "sanctioned-countries", "order:export"),
            platform.bindings,
        )
        harness.bootstrap(app, listOf(policy("perm:order/approve", "app-secret-rule", "order:approve")), emptyList())
        val tenant = plane.read(harness.alice, TENANT)
        harness.bootstrap(
            TENANT,
            tenant.policies,
            tenant.bindings + listOf(
                Binding(
                    BindingId("base-access"),
                    SubjectRef.Anyone,
                    BindingScope(Match.Any, Match.Any),
                    PolicyId("platform-base")
                ),
                Binding(
                    BindingId("order-access"),
                    SubjectRef.Anyone,
                    BindingScope(Match.Exactly(ORDER), Match.Any),
                    PolicyId("perm:order/approve")
                ),
            ),
        )
    }

    @Test
    fun `a tenant administrator does not see the statements of a platform policy their binding points to`() =
        runBlocking<Unit> {
            fixtures()
            val request = harness.bob("export")

            val simulated = plane.simulate(harness.alice, request)
            simulated.enforced.decision.assert().isEqualTo("Permit")
            simulated.enforced.by.assert().isNull()
            simulated.enforced.redacted.assert().isTrue()
            simulated.enforcedTrace!!.entries.assert().noneMatch { it.statement.policy == "platform-base" }
            simulated.enforcedTrace!!.redacted.assert().isGreaterThan(0)

            val explained = plane.explain(harness.alice, request, null)
            explained.decision.by.assert().isNull()
            explained.decision.redacted.assert().isTrue()
            explained.trace!!.entries.assert().noneMatch { it.statement.policy == "platform-base" }
        }

    @Test
    fun `a tenant administrator does not see the statements of an app policy their binding points to`() =
        runBlocking<Unit> {
            fixtures()
            val request = harness.bob("approve")

            val simulated = plane.simulate(harness.alice, request)
            simulated.enforced.decision.assert().isEqualTo("Permit")
            simulated.enforced.by.assert().isNull()
            simulated.enforced.redacted.assert().isTrue()
            simulated.enforcedTrace!!.entries.assert().noneMatch { it.statement.policy == "perm:order/approve" }
            simulated.enforcedTrace!!.redacted.assert().isGreaterThan(0)

            val explained = plane.explain(harness.alice, request, null)
            explained.decision.by.assert().isNull()
            explained.trace!!.entries.assert().noneMatch { it.statement.policy == "perm:order/approve" }
        }

    @Test
    fun `a platform operator still sees them, and a tenant administrator their own tenant's statements`() =
        runBlocking<Unit> {
            fixtures()

            val platform = plane.simulate(harness.plato, harness.bob("export"))
            platform.enforced.by!!.policy.assert().isEqualTo("platform-base")
            platform.enforced.by!!.name.assert().isEqualTo("sanctioned-countries")
            platform.enforced.by!!.via.assert().isEqualTo("base-access")
            platform.enforced.redacted.assert().isFalse()
            platform.enforcedTrace!!.entries.assert().anyMatch { it.statement.policy == "platform-base" }

            val application = plane.simulate(harness.plato, harness.bob("approve"))
            application.enforced.by!!.policy.assert().isEqualTo("perm:order/approve")
            application.enforced.by!!.name.assert().isEqualTo("app-secret-rule")

            // A policy the tenant defines is the tenant's own to see, whatever binds it.
            val own = plane.simulate(harness.alice, harness.bob("read"))
            own.enforced.by!!.policy.assert().isEqualTo("orders")
            own.enforced.redacted.assert().isFalse()
        }
}
