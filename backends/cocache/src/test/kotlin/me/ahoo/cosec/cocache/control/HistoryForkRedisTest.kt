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

package me.ahoo.cosec.cocache.control

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.snapshot.RedisTarget
import me.ahoo.cosec.cocache.snapshot.SnapshotNode
import me.ahoo.cosec.cocache.snapshot.newKeyPrefix
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.HeadWrite
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * #1536 on real Redis: the head moved back and a revision number was reused, with the index write of the second
 * publication failing. Another instance, with nothing local, reads the head's chain, not the old branch.
 */
class HistoryForkRedisTest {
    private val backend: CoherenceBackend = RedisTarget.REDIS.backend()
    private val keyPrefix = newKeyPrefix()
    private val key = SnapshotKey.Tenant("acme")
    private val nodes = ArrayList<SnapshotNode>()

    @AfterEach
    fun close() {
        nodes.forEach(SnapshotNode::close)
        backend.close()
    }

    private fun store(): CoCacheControlStore {
        val node = SnapshotNode(backend, keyPrefix).also { nodes += it }
        return CoCacheControlStore.create(node.factory, node.cache, keyPrefix, node.store::notifyChanged)
    }

    private fun candidate(policy: String) = PolicyDocument(
        DocumentKind.TENANT,
        "acme",
        1,
        Instant.EPOCH,
        listOf(PolicyDefinition(PolicyId(policy), false, null, listOf(StatementSource("", Effect.ALLOW, listOf("*"))))),
        emptyList(),
    )

    private val publication = Publication("c", ProvenanceKind.PUBLISH, Actor(TenantId("acme"), PrincipalId("alice")))

    private suspend fun Revisions.publishOver(policy: String) =
        (publish(key, head(key), candidate(policy), publication) as PublishOutcome.Published).digest

    @Test
    fun `a reused revision number reads the head's chain from another instance`() = runBlocking<Unit> {
        val real = store()
        var failFor: SnapshotDigest? = null
        val failing = object : ControlStore by real {
            override suspend fun putIndex(key: SnapshotKey, expected: SnapshotDigest?, index: RevisionIndex): Boolean {
                val old = failFor
                if (old != null && index.entries.any { it.revision == 2L && it.digest != old }) {
                    failFor = null
                    error("the index write failed")
                }
                return real.putIndex(key, expected, index)
            }
        }
        val revisions = Revisions(failing)
        val first = revisions.publishOver("base")
        val old = revisions.publishOver("old-branch")
        real.putHead(key, old, real.record(key, first)!!).assert().isInstanceOf(HeadWrite.Written::class.java)
        failFor = old
        val new = revisions.publishOver("new-branch")
        revisions.publishOver("third")

        val other = Revisions(store())
        other.history(key).map { it.revision }.assert().containsExactly(1L, 2L, 3L)
        other.history(key)[1].digest.assert().isEqualTo(new)
        other.revision(key, 2)!!.policies.map { it.id.value }.assert().containsExactly("new-branch")
        other.rollbackCandidate(key, 2).policies.map { it.id.value }.assert().containsExactly("new-branch")
        real.index(key)!!.value.reclaiming.assert().contains(old)
    }
}
