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
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * RFC 0007 N2 and §9.4 (#1224, option A): a tenant document may hold a policy of a platform policy's id only as an exact
 * copy of it, which is the first step of the partition flow. The platform's policy is frozen while a tenant holds a copy,
 * so the control plane never enters the state the engine fails closed on: one id, two contents.
 */
class SharedPolicyCopyTest {
    private val harness = ChangeHarness(options = ChangeOptions(shadow = IMMEDIATE_WINDOW))
    private val changes = harness.changes
    private val platform = SnapshotKey.Platform

    private suspend fun propose(key: SnapshotKey, vararg operations: Operation): ChangeRequest =
        changes.propose(operatorFor(key), key, harness.revisions.head(key)?.revision ?: 0, operations.toList(), "test")

    private suspend fun publish(key: SnapshotKey, vararg operations: Operation): ChangeRequest {
        val change = propose(key, *operations)
        change.errors.assert().describedAs("${change.findings}").isEmpty()
        changes.skipShadow(operatorFor(key), change.id, "test")
        return changes.publish(operatorFor(key), change.id)
    }

    @Test
    fun `a tenant takes a platform policy's id as an exact copy`() = runBlocking<Unit> {
        publish(platform, Operation.PutPolicy(allow("shared")))

        publish(ACME, Operation.PutPolicy(allow("shared"))).state.assert().isEqualTo(ChangeState.PUBLISHED)

        harness.revisions.head(ACME)!!.document.policies.map { it.id.value }.assert().containsExactly("shared")
    }

    @Test
    fun `a tenant policy of that id that is not a copy is refused, and so is any edit of the copy`() = runBlocking<Unit> {
        publish(platform, Operation.PutPolicy(allow("shared")))

        val different = propose(ACME, Operation.PutPolicy(allow("shared", action = "order:*")))
        different.errors.map { it.check to it.path }.assert().containsExactly("V2" to "$.policies[0].id")
        different.errors.single().message.assert().contains("[shared]").contains("exact copy")
        changes.abandon(operatorFor(ACME), different.id)

        publish(ACME, Operation.PutPolicy(allow("shared")))
        propose(ACME, Operation.PutPolicy(allow("shared", action = "order:list"))).errors.map { it.check }
            .assert().containsExactly("V2")
    }

    @Test
    fun `the platform policy is frozen while a tenant holds a copy, and free again once it does not`() = runBlocking<Unit> {
        publish(platform, Operation.PutPolicy(allow("shared")), Operation.PutPolicy(allow("other")))
        publish(ACME, Operation.PutPolicy(allow("shared")))

        val edit = propose(platform, Operation.PutPolicy(allow("shared", action = "order:*")))
        edit.errors.map { it.check to it.path }.assert().containsExactly("V2" to "$.policies[0].id")
        edit.errors.single().message.assert().contains("tenant:acme")
        changes.abandon(operatorFor(platform), edit.id)
        // A policy no tenant copies stays editable.
        val other = propose(platform, Operation.PutPolicy(allow("other", action = "order:*")))
        other.errors.assert().isEmpty()
        changes.abandon(operatorFor(platform), other.id)

        publish(ACME, Operation.RemovePolicy(PolicyId("shared")))
        propose(platform, Operation.PutPolicy(allow("shared", action = "order:*"))).errors.assert().isEmpty()
    }

    @Test
    fun `the platform may remove its policy while a tenant holds a copy`() = runBlocking<Unit> {
        publish(platform, Operation.PutPolicy(allow("shared")))
        publish(ACME, Operation.PutPolicy(allow("shared")), Operation.PutBinding(bind("staff", "shared")))

        publish(platform, Operation.RemovePolicy(PolicyId("shared")))

        harness.revisions.head(ACME)!!.document.policies.map { it.id.value }.assert().containsExactly("shared")
    }

    /** #1224 as the issue ran it: a plan holding an exact copy of a published platform policy goes through `apply`. */
    @Test
    fun `apply merges the copy a partition plan holds`() = runBlocking<Unit> {
        publish(platform, Operation.PutPolicy(allow("p")))
        val plan = StoreFixtures.candidate(ACME, listOf(allow("p")))

        val change = changes.apply(PLATO, ACME, plan, ApplyMode.MERGE, "partition")

        change.errors.assert().describedAs("${change.findings}").isEmpty()
        change.state.assert().isEqualTo(ChangeState.CHECKED)
        change.operations.map { it::class.simpleName }.assert().containsExactly("PutPolicy")
    }
}
