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
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.DocumentBudgets
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * `CTL-40` in cosec-migrate: a converted document over a default budget is a warning naming the budget, the count and
 * the limit, and is still written; `publish` and `restore` write it as it is, since neither goes through the control
 * plane's checks, and hosts serve it.
 */
class ConvertedBudgetsTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC)
    private val acme = SnapshotKey.Tenant("acme")
    private val over = DocumentBudgets.DEFAULT_MAX_ANYONE_BINDINGS + 1

    private val policy = PolicyDefinition(
        PolicyId("p"),
        attachable = false,
        condition = null,
        statements = listOf(StatementSource("", Effect.ALLOW, listOf("order:read"), emptyList())),
    )

    private fun tenant(anyone: Int) = PolicyDocument(
        DocumentKind.TENANT,
        "acme",
        1,
        clock.instant(),
        listOf(policy),
        (0 until anyone).map {
            Binding(BindingId("b$it"), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), PolicyId("p"))
        },
    )

    private val platform = PolicyDocument(DocumentKind.PLATFORM, "", 1, clock.instant(), emptyList(), emptyList())

    @Test
    fun `a document over a default budget is a warning naming it, one within every budget is none`() {
        ConvertedBudgets.warnings(acme, tenant(over)).single().let {
            it.location.assert().isEqualTo("tenant:acme#$.bindings")
            it.message.assert().contains("holds $over bindings to Anyone")
                .contains("limits.maxAnyoneBindings (${DocumentBudgets.DEFAULT_MAX_ANYONE_BINDINGS}, CTL-40)")
                .contains("the first change shrink it")
        }
        ConvertedBudgets.warnings(acme, tenant(DocumentBudgets.DEFAULT_MAX_ANYONE_BINDINGS)).assert().isEmpty()
    }

    @Test
    fun `publish and restore write a document over a budget as it is`() {
        val store = InMemoryControlStore(clock)
        DocumentPublisher(store, clock).publish(mapOf(SnapshotKey.Platform to platform, acme to tenant(1)))
        val restorer = DocumentRestorer(store, clock)
        restorer.restore(restorer.plan(acme, null, tenant(over)), confirmed = 1).assert().isEqualTo(2)
        runBlocking { Revisions(store, clock).head(acme)!! }.document.bindings.size.assert().isEqualTo(over)

        val fresh = InMemoryControlStore(clock)
        DocumentPublisher(fresh, clock).publish(mapOf(SnapshotKey.Platform to platform, acme to tenant(over)))
        runBlocking { Revisions(fresh, clock).head(acme)!! }.document.bindings.size.assert().isEqualTo(over)
    }
}
