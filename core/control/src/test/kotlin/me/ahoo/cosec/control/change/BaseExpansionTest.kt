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
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RoleDefinition
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.document.RolePermissions
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * DR1-E-10: a head whose roles derive more than the role grant limit (one `cosec-migrate` or a restore wrote) cannot be
 * compared with what refers to its policies (`CTL-30`). A change of it is refused by name, rather than checked and then
 * failing at its head write.
 */
class BaseExpansionTest {
    private val harness = ChangeHarness()
    private val key = SnapshotKey.App("order")

    @Test
    fun `a change of a head that does not expand is refused, naming the base`() = runBlocking<Unit> {
        val oversized = RoleDefinition(
            RoleId("everything"),
            RolePermissions.Listed((0..RoleExpansion.DEFAULT_ROLE_GRANT_LIMIT).map { "p$it" }),
        )
        val head = PolicyDocument(
            DocumentKind.APP,
            "order",
            1,
            StoreFixtures.START,
            listOf(allow("perm:order/read")),
            emptyList(),
            roles = listOf(oversized),
        )
        harness.memory.putHead(key, null, SnapshotDocuments.of(head))

        val fix = harness.changes.propose(operatorFor(key), key, 1, listOf(Operation.RemoveRole(oversized.id)), "test")

        fix.state.assert().isEqualTo(ChangeState.DRAFT)
        fix.errors.single { it.check == "REFERENCES" }.let {
            it.message.assert().contains("base revision of [$key]").contains("does not expand")
            it.status.assert().isEqualTo(422)
        }
    }
}
