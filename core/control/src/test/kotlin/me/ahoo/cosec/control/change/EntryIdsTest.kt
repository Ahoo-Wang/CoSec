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
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.flat.RateLimitSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.runtime.document.RoleDefinition
import me.ahoo.cosec.runtime.document.RolePermissions
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * RFC 0007 §7.1: an entry's id is one segment of the resource `cosec:write` is decided on. An id with a `/` adds a
 * segment: a rule whose last segment is a `*` does not reach it, so it walks out of a DENY written that way.
 */
class EntryIdsTest {
    private val harness = ChangeHarness()
    private val changes = harness.changes

    private val role = RoleDefinition(
        RoleId("night/shift"),
        RolePermissions.Listed(emptyList()),
        app = AppId("wms"),
    )
    private val quota = RateLimitSource(
        "per/minute",
        StatementSource("per/minute", Effect.DENY, listOf("*")),
        key = null,
        limit = RateLimitSpec(10.0),
    )

    private suspend fun proposed(key: SnapshotKey, vararg operations: Operation): ChangeRequest =
        changes.propose(operatorFor(key), key, harness.revisions.head(key)?.revision ?: 0, operations.toList(), "test")

    private suspend fun assertRefused(operation: Operation) {
        val change = proposed(ACME, Operation.PutPolicy(allow("p")), operation)
        change.state.assert().isEqualTo(ChangeState.DRAFT)
        change.errors.single { it.path == "operations[1]" }.let {
            it.check.assert().isEqualTo("V2")
            it.status.assert().isEqualTo(422)
        }
    }

    @Test
    fun `an id that would add a segment to its resource is refused`() = runBlocking<Unit> {
        assertRefused(Operation.PutPolicy(allow("a/b")))
        assertRefused(Operation.PutBinding(bind("a/b", "p")))
        assertRefused(Operation.PutRole(role))
        assertRefused(Operation.PutRateLimit(quota))
    }

    @Test
    fun `a permission policy keeps the slash its id has`() = runBlocking<Unit> {
        val key = SnapshotKey.App("order")
        proposed(key, Operation.PutPolicy(allow("perm:order/read"))).errors.assert().isEmpty()
    }

    @Test
    fun `an entry with a slash from before can still be replaced by nothing`() = runBlocking<Unit> {
        harness.revisions.publish(
            ACME,
            null,
            StoreFixtures.candidate(ACME, listOf(allow("p")), listOf(bind("a/b", "p"))),
            Publication("seed", ProvenanceKind.MIGRATE, PLATO.actor),
        )
        val removal = proposed(ACME, Operation.RemoveBinding(BindingId("a/b")))
        removal.errors.assert().isEmpty()
        // Entries the change leaves alone are not looked at.
        proposed(ACME, Operation.PutPolicy(allow("q"))).errors.assert().isEmpty()
    }
}
