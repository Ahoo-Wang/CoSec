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

package me.ahoo.cosec.cache.control

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cosec.cache.snapshot.RedisTarget
import me.ahoo.cosec.cache.snapshot.SnapshotNode
import me.ahoo.cosec.cache.snapshot.newKeyPrefix
import me.ahoo.cosec.control.store.ChainCheck
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotRead
import me.ahoo.cosec.snapshot.document.Actor
import me.ahoo.cosec.snapshot.document.PolicyDefinition
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import me.ahoo.cosec.snapshot.document.ProvenanceKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The revision chain of RFC 0007 §5.4 on real Redis (AGENTS.md: no mocks for caching and concurrency): records before
 * heads, every head naming its parent, conflicts between control-plane instances, and the data plane reading only
 * whole revisions (P7).
 */
class RevisionChainTest {
    private val backend: CoherenceBackend = RedisTarget.REDIS.backend()
    private val keyPrefix = newKeyPrefix()
    private val key = SnapshotKey.Tenant("acme")
    private val nodes = ArrayList<SnapshotNode>()
    private val stores = HashMap<SnapshotNode, CoCacheControlStore>()

    @AfterEach
    fun close() {
        nodes.forEach(SnapshotNode::close)
        backend.close()
    }

    /** One control-plane instance: its own runtime over the shared Redis. */
    private fun instance(): Pair<SnapshotNode, Revisions> {
        val node = SnapshotNode(backend, keyPrefix).also { nodes += it }
        val store = CoCacheControlStore.create(node.runtime, node.cache, keyPrefix, node.store::notifyChanged)
        stores[node] = store
        return node to Revisions(store)
    }

    private fun candidate(vararg policies: String) = PolicyDocument(
        DocumentKind.TENANT,
        "acme",
        1,
        Instant.EPOCH,
        policies.map {
            PolicyDefinition(
                PolicyId(it),
                false,
                null,
                listOf(StatementSource("", Effect.ALLOW, listOf("*")))
            )
        },
        emptyList(),
    )

    private fun publication(change: String) = Publication(
        change,
        ProvenanceKind.PUBLISH,
        Actor(TenantId("acme"), PrincipalId("alice"))
    )

    private suspend fun Revisions.publishOver(change: String, vararg policies: String) =
        publish(key, head(key), candidate(*policies), publication(change))

    @Test
    fun `every head names its parent, whose record is in Redis`() = runBlocking<Unit> {
        val (node, revisions) = instance()
        val digests = (1..3).map { (revisions.publishOver("c$it", "p$it") as PublishOutcome.Published).digest }
        val read = node.store.readIfChanged(key, null).get() as SnapshotRead.Present
        read.document.revision.assert().isEqualTo(3)
        // The data plane's digest of the head is the control plane's: the record address of the next parent.
        read.digest.assert().isEqualTo(digests[2])
        revisions.history(key).map { it.digest }.assert().isEqualTo(digests)
        revisions.verifyChain(key).assert().isInstanceOf(ChainCheck.Intact::class.java)
        val head = revisions.head(key)!!.document
        head.provenance!!.parent!!.digest.assert().isEqualTo(digests[1].toString())
        // Another instance, with nothing local, finds the same chain in Redis.
        val (_, other) = instance()
        other.history(key).map { it.revision }.assert().containsExactly(1L, 2L, 3L)
        other.revision(key, 1)!!.policies.map { it.id.value }.assert().containsExactly("p1")
    }

    @Test
    fun `two instances publishing on the same base never overwrite each other`() = runBlocking<Unit> {
        val (_, first) = instance()
        val (_, second) = instance()
        first.publishOver("base", "base")
        repeat(10) { round ->
            val base = first.head(key)
            val outcomes = listOf(first, second).mapIndexed { index, revisions ->
                async { revisions.publish(key, base, candidate("r$round-$index"), publication("r$round-$index")) }
            }.awaitAll()
            outcomes.count { it is PublishOutcome.Published }.assert().isEqualTo(1)
            outcomes.count { it is PublishOutcome.Conflicted }.assert().isEqualTo(1)
            val winner = outcomes.indexOfFirst { it is PublishOutcome.Published }
            first.head(key)!!.document.policies.single().id.value.assert().isEqualTo("r$round-$winner")
        }
        first.verifyChain(key).assert().isInstanceOf(ChainCheck.Intact::class.java)
        second.history(key).map { it.revision }.assert().isEqualTo((1L..11L).toList())
    }

    /** P7: the head is one key, so a reader sees one whole revision, old or new, whatever it races with. */
    @Test
    fun `the data plane reads only whole revisions`() = runBlocking<Unit> {
        val (node, revisions) = instance()
        revisions.publishOver("c0", "p0")
        val done = AtomicBoolean()
        val errors = ArrayList<String>()
        val reader = thread {
            while (!done.get()) {
                val read = node.store.readIfChanged(key, null).get() as SnapshotRead.Present
                val document = PolicyDocumentCodec.decode(DocumentKind.TENANT, "acme", read.document.bodyBytes())
                val expected = "p${document.revision - 1}"
                if (document.policies.single().id.value != expected || (document.provenance?.parent?.revision ?: 0) != document.revision - 1) {
                    synchronized(
                        errors
                    ) { errors += "revision ${document.revision} holds ${document.policies.map { it.id }}" }
                }
            }
        }
        (1..30).forEach { revisions.publishOver("c$it", "p$it") }
        done.set(true)
        reader.join()
        errors.assert().isEmpty()
    }

    /** RFC 0007 §5.5, §6.1 on Redis: one staged change per document across instances, published through putIfMatch. */
    @Test
    fun `changes stage one at a time across instances and publish once`() = runBlocking<Unit> {
        val (firstNode, first) = instance()
        val (secondNode, second) = instance()
        val ids = java.util.concurrent.atomic.AtomicInteger()
        fun changes(node: SnapshotNode, revisions: Revisions) = me.ahoo.cosec.control.change.Changes(
            stores.getValue(node),
            revisions,
            { me.ahoo.cosec.control.change.ChangeId("rc${ids.incrementAndGet()}") },
            // The change flow across instances, not authorization: every action is permitted.
            { _, _, _ -> me.ahoo.cosec.control.authz.ControlDecision.PERMIT },
        )
        val one = changes(firstNode, first)
        val two = changes(secondNode, second)
        val author = me.ahoo.cosec.control.authz.Operator(
            Actor(TenantId("acme"), PrincipalId("alice")),
            me.ahoo.cosec.model.Memberships(me.ahoo.cosec.model.Memberships.Source.CLAIMS, emptyMap(), emptyList()),
        )
        val a = one.propose(
            author,
            key,
            0,
            listOf(me.ahoo.cosec.control.change.Operation.PutPolicy(candidate("a").policies.single())),
            "a"
        )
        val b = two.propose(
            author,
            key,
            0,
            listOf(me.ahoo.cosec.control.change.Operation.PutPolicy(candidate("b").policies.single())),
            "b"
        )
        one.stage(author, a.id)
        org.junit.jupiter.api.assertThrows<me.ahoo.cosec.control.change.ChangeRejectedException> {
            runBlocking {
                two.stage(
                    author,
                    b.id
                )
            }
        }
        one.shadowed(a.id)
        one.publish(author, a.id).publishedRevision.assert().isEqualTo(1)
        // The slot is free; b's base is gone, so it conflicts instead of overwriting, and rebases onto revision 1.
        two.skipShadow(author, b.id, "test")
        two.publish(author, b.id).state.assert().isEqualTo(me.ahoo.cosec.control.change.ChangeState.CONFLICTED)
        two.rebase(author, b.id).base.revision.assert().isEqualTo(1)
        second.head(key)!!.document.policies.map { it.id.value }.assert().containsExactly("a")
    }

    @Test
    fun `one instance reclaims at a time`() = runBlocking<Unit> {
        val (_, first) = instance()
        val (_, second) = instance()
        first.publishOver("c", "p")
        val node = SnapshotNode(backend, keyPrefix).also { nodes += it }
        val store = CoCacheControlStore.create(node.runtime, node.cache, keyPrefix)
        store.acquireLease("reclaim:$key", "elsewhere", java.time.Duration.ofMinutes(5)).assert().isTrue()
        store.acquireLease("reclaim:$key", "elsewhere", java.time.Duration.ofMinutes(5)).assert().isTrue()
        store.acquireLease("reclaim:$key", "another", java.time.Duration.ofMinutes(5)).assert().isFalse()
        first.reclaim(key).records.assert().isEmpty()
        second.reclaim(key).orphans.assert().isEmpty()
    }
}
