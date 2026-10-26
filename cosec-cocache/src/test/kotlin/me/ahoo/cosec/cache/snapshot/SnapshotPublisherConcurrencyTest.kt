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

package me.ahoo.cosec.cache.snapshot

import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotRead
import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reactor.core.publisher.Mono
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * RFC 0003 §6 F12 on real Redis, Valkey and a Redis Cluster: writers on several instances change one document at
 * once, and no update is lost. Each instance has its own CoCache runtime, so its own local copies, as separate
 * processes would; every writer starts at the same moment, so the writes contend (the conflicts prove they did).
 */
class SnapshotPublisherConcurrencyTest {
    private fun policy(id: String) = PolicyData(id, "c", id, "", PolicyType.CUSTOM, "t")

    /** Runs [write] for every (instance, thread, index) at once and waits for all of them. */
    private fun contend(nodes: List<SnapshotNode>, write: (SnapshotNode, String) -> Mono<Void>) {
        val executor = Executors.newFixedThreadPool(nodes.size * THREADS)
        val start = CountDownLatch(1)
        try {
            val futures = nodes.flatMapIndexed { instance, node ->
                (0 until THREADS).map { thread ->
                    executor.submit {
                        start.await()
                        repeat(WRITES) { index -> write(node, "i$instance-t$thread-w$index").block() }
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun withNodes(target: RedisTarget, test: (List<SnapshotNode>) -> Unit) {
        val keyPrefix = newKeyPrefix()
        target.backend().use { backend ->
            val nodes = List(INSTANCES) { SnapshotNode(backend, keyPrefix) }
            try {
                test(nodes)
            } finally {
                nodes.forEach { it.close() }
            }
        }
    }

    private val expectedIds = (0 until INSTANCES).flatMap { instance ->
        (0 until THREADS).flatMap { thread -> (0 until WRITES).map { "i$instance-t$thread-w$it" } }
    }

    @ParameterizedTest
    @EnumSource(RedisTarget::class)
    fun concurrentPolicyWritesAreAllKept(target: RedisTarget) {
        withNodes(target) { nodes ->
            contend(nodes) { node, id -> node.publisher.setPolicy(policy(id)) }

            val read = nodes.first().store.readIfChanged(SnapshotKey.Policies, null).get() as SnapshotRead.Present
            val content = SnapshotContent.read(SnapshotKey.Policies, read.document) as SnapshotContent.Policies
            content.policies.keys.assert().containsExactlyInAnyOrderElementsOf(expectedIds)
            // one revision per write: none was applied on top of a stale read
            read.document.revision.assert().isEqualTo(expectedIds.size.toLong())
            nodes.sumOf { it.publisher.conflicts }.assert().isGreaterThan(0L)
            nodes.sumOf {
                checkNotNull(
                    it.runtime.metrics(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME)
                ).conditionalConflicts
            }
                .assert().isEqualTo(nodes.sumOf { it.publisher.conflicts })
        }
    }

    @ParameterizedTest
    @EnumSource(RedisTarget::class)
    fun concurrentGrantWritesAreAllKept(target: RedisTarget) {
        withNodes(target) { nodes ->
            nodes.first().publisher.setAppPermission(appPermission("app")).block()
            contend(
                nodes
            ) { node, role -> node.publisher.setRoleGrants("app", role, SpaceIdCapable.DEFAULT, setOf("p1")) }

            val key = SnapshotKey.App("app")
            val read = nodes.last().store.readIfChanged(key, null).get() as SnapshotRead.Present
            val content = SnapshotContent.read(key, read.document) as SnapshotContent.App
            content.grants.keys.assert().containsExactlyInAnyOrderElementsOf(expectedIds)
            read.document.revision.assert().isEqualTo(expectedIds.size + 1L)
            nodes.sumOf { it.publisher.conflicts }.assert().isGreaterThan(0L)
        }
    }

    /**
     * Review: every instance writing what is already there, such as `LocalPolicyInitializer` on a rolling restart,
     * publishes nothing: no revision churn, no invalidations, no reloads.
     */
    @ParameterizedTest
    @EnumSource(RedisTarget::class)
    fun concurrentWritesOfWhatIsThereChangeNothing(target: RedisTarget) {
        withNodes(target) { nodes ->
            nodes.first().publisher.setPolicies(listOf(policy("a"), policy("b"))).block()
            contend(nodes) { node, _ -> node.publisher.setPolicies(listOf(policy("a"), policy("b"))) }

            val read = nodes.first().store.readIfChanged(SnapshotKey.Policies, null).get() as SnapshotRead.Present
            read.document.revision.assert().isEqualTo(1L)
            nodes.sumOf { it.publisher.conflicts }.assert().isZero()
        }
    }

    companion object {
        private const val INSTANCES = 3
        private const val THREADS = 4
        private const val WRITES = 5
        private const val TIMEOUT_SECONDS = 120L
    }
}
