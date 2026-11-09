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
import me.ahoo.cosec.control.grant.GrantDocuments
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * RFC 0007 §5.8, §7.6 and §9.1: what refers to a platform policy depends on its being a policy or a boundary. A binding
 * reaches only a policy and a tenant's `boundary` only a boundary, so one that changes kind under them is as gone as a
 * removed one: the request fails closed. A change of kind that something refers to is refused, and nothing binds a
 * boundary.
 */
class BoundaryReferencesTest {
    private val harness = ChangeHarness()
    private val changes = harness.changes
    private val platform: SnapshotKey = SnapshotKey.Platform

    private val shared = allow("shared")
    private val fence = PolicyDefinition(PolicyId("fence"), false, null, allow("fence").statements, boundary = true)

    private fun boundary(policy: PolicyDefinition) =
        PolicyDefinition(policy.id, false, null, policy.statements, boundary = true)

    private fun ordinary(policy: PolicyDefinition) = PolicyDefinition(policy.id, false, null, policy.statements)

    private suspend fun proposed(key: SnapshotKey, vararg operations: Operation): ChangeRequest =
        changes.propose(operatorFor(key), key, harness.revisions.head(key)?.revision ?: 0, operations.toList(), "test")

    private suspend fun publish(key: SnapshotKey, vararg operations: Operation): ChangeRequest {
        val change = proposed(key, *operations)
        change.errors.assert().describedAs("${change.findings}").isEmpty()
        changes.skipShadow(operatorFor(key), change.id, "test")
        return changes.publish(operatorFor(key), change.id)
    }

    private fun ChangeRequest.assertRefusedAsReferred() {
        state.assert().isEqualTo(ChangeState.DRAFT)
        errors.single().let {
            it.check.assert().isEqualTo("REFERENCES")
            it.status.assert().isEqualTo(422)
            it.message.assert().contains("tenant:acme")
        }
    }

    @Test
    fun `a policy a tenant binds does not become a boundary`() = runBlocking<Unit> {
        publish(platform, Operation.PutPolicy(shared))
        publish(ACME, Operation.PutBinding(bind("b", "shared")))
        proposed(platform, Operation.PutPolicy(boundary(shared))).assertRefusedAsReferred()
    }

    @Test
    fun `a boundary a tenant names does not become a policy`() = runBlocking<Unit> {
        publish(platform, Operation.PutPolicy(fence))
        publish(ACME, Operation.SetBoundary(fence.id))
        proposed(platform, Operation.PutPolicy(ordinary(fence))).assertRefusedAsReferred()
    }

    @Test
    fun `a policy nothing refers to changes kind, and a change of content is not one`() = runBlocking<Unit> {
        publish(platform, Operation.PutPolicy(shared))
        publish(ACME, Operation.PutBinding(bind("b", "shared")))
        publish(platform, Operation.PutPolicy(allow("shared", action = "order:list"))).state
            .assert().isEqualTo(ChangeState.PUBLISHED)
        publish(platform, Operation.PutPolicy(allow("free")))
        publish(platform, Operation.PutPolicy(boundary(allow("free")))).state.assert().isEqualTo(ChangeState.PUBLISHED)
        harness.revisions.head(
            platform
        )!!.document.policies.single { it.id == PolicyId("free") }.boundary.assert().isTrue()
    }

    @Test
    fun `the head write refuses a change of kind too, for a reference added after the check`() = runBlocking<Unit> {
        publish(platform, Operation.PutPolicy(shared))
        publish(ACME, Operation.PutBinding(bind("b", "shared")))
        val head = harness.revisions.head(platform)!!
        val candidate =
            PolicyDocument(
                head.document.kind,
                head.document.owner,
                1,
                head.document.publishedAt,
                listOf(boundary(shared)),
                emptyList()
            )
        val refused = harness.revisions.publish(
            platform,
            head,
            candidate,
            Publication("c", ProvenanceKind.PUBLISH, PLATO.actor)
        )
        (refused as PublishOutcome.Referenced).referrers.assert().isEqualTo(mapOf(shared.id to setOf(ACME)))
        harness.revisions.head(platform)!!.revision.assert().isEqualTo(head.revision)
    }

    @Test
    fun `a tenant does not bind a platform boundary`() = runBlocking<Unit> {
        publish(platform, Operation.PutPolicy(fence))
        val refused = proposed(ACME, Operation.PutBinding(bind("b", "fence")))
        refused.state.assert().isEqualTo(ChangeState.DRAFT)
        refused.errors.single().let {
            it.check.assert().isEqualTo("REFERENCES")
            it.path.assert().isEqualTo("$.bindings[0].policy")
            it.status.assert().isEqualTo(422)
        }
    }

    @Test
    fun `an app does not bind a platform boundary either`() = runBlocking<Unit> {
        publish(platform, Operation.PutPolicy(fence))
        val refused = proposed(SnapshotKey.App("order"), Operation.PutBinding(bind("b", "fence")))
        refused.errors.map { it.check }.assert().containsExactly("REFERENCES")
    }

    @Test
    fun `a grant analysis reads a boundary as no policy at all`() {
        val document = PolicyDocument(
            kind = me.ahoo.cosec.model.decision.DocumentKind.PLATFORM,
            owner = "",
            revision = 1,
            publishedAt = java.time.Instant.EPOCH,
            policies = listOf(shared, fence),
            bindings = emptyList(),
        )
        val read = GrantDocuments.of(document)
        read.policy(shared.id).assert().isNotNull()
        read.policy(fence.id).assert().isNull()
    }
}
