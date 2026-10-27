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

import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.cache.AppSpacedRoleId
import me.ahoo.cosec.cache.CoSecTestCaches
import me.ahoo.cosec.explain.ExplainLayer
import me.ahoo.cosec.migrate.CoSec7xReader
import me.ahoo.cosec.parity.AuthorizationFactory
import me.ahoo.cosec.parity.DecisionParity
import me.ahoo.cosec.parity.FailureView
import me.ahoo.cosec.parity.LegacyCases
import me.ahoo.cosec.parity.LegacyData
import me.ahoo.cosec.parity.ParityResult
import me.ahoo.cosec.parity.SnapshotAuthorizationFixture
import me.ahoo.cosec.snapshot.SnapshotOptions
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.ForkJoinPool

/**
 * RFC 0003 §8.2, random cases: for generated 7.x data and requests, the engine over 7.0's Redis repositories (phase 2's
 * implementation) and the engine over the snapshot of the same data decide alike, compared with [DecisionParity] as the
 * gate compares them (failures by their innermost cause, §8.1).
 *
 * The snapshot side takes the path real data takes: the 7.x data is stored as JSON in 7.0's layout, undecodable entries
 * included, [CoSec7xSnapshotReader] converts it, [RedisSnapshotPublisher] publishes it, and the snapshot source reads it back
 * through [RedisSnapshotDocumentStore] and compiles it. Both sides run on CoCache's in-memory backend.
 *
 * `check` runs [ITERATIONS] cases from a fixed seed on one thread; `./gradlew :cosec-cocache:snapshotParity` runs a
 * million (`-PparityIterations=N` for another count) on `-PparityThreads` threads, 4 by default. The cases are the same
 * whatever the thread count: one generator makes them in order. The P5 property tests compare the policy sets themselves; this one
 * compares what reaches the caller.
 */
class SnapshotDecisionParityPropertyTest {
    @Test
    fun snapshotAndRepositoriesDecideAlike() {
        val cases = LegacyCases(SEED, brokenEntries = true, failedDocuments = false)
        var decided = 0
        var bothFailed = 0
        var roleDecided = 0
        val pool = ForkJoinPool(THREADS)
        try {
            // Cases come from one generator, in order; each batch is then compared on THREADS threads.
            (0 until ITERATIONS).chunked(BATCH).forEach { batch ->
                val generated = batch.map { it to cases.next() }
                val results = pool.submit<List<Pair<Int, ParityResult>>> {
                    generated.parallelStream().map { (iteration, case) ->
                        iteration to compare(case.data) { parity -> parity.compare(case.request, case.principal) }
                    }.toList()
                }.get()
                for ((iteration, result) in results) {
                    if (result.isDifference) {
                        throw AssertionError(
                            "Case $iteration differs (${result.verdict}): baseline ${result.baseline.tuple} " +
                                "(${result.baseline.failureCause}), candidate ${result.candidate.tuple} " +
                                "(${result.candidate.failureCause})",
                        )
                    }
                    if (result.bothFailed) bothFailed++ else decided++
                    if (result.baseline.tuple.decidedBy?.layer == ExplainLayer.ROLE_PERMISSION) roleDecided++
                }
            }
        } finally {
            pool.shutdownNow()
        }
        println(
            "Snapshot decision parity: $ITERATIONS cases on $THREADS thread(s), $decided decided, " +
                "$bothFailed failed alike, $roleDecided decided by a role.",
        )
        // The comparison means something only if both decisions and failures occur often, roles included.
        decided.assert().isGreaterThan(ITERATIONS / 2)
        bothFailed.assert().isGreaterThan(ITERATIONS / 50)
        roleDecided.assert().isGreaterThan(ITERATIONS / 100)
    }

    /** Stores [data] as 7.x data and compares 7.0's repositories over it with the snapshot converted from it. */
    private fun compare(data: LegacyData, compare: (DecisionParity) -> ParityResult): ParityResult {
        val backend = InMemoryBackend()
        return CoSecTestCaches(backend).use { caches ->
            Raw7xWriter(backend, caches.keyPrefix).use { it.store(data) }
            SnapshotNode(backend, caches.keyPrefix).use { node ->
                val conversion = CoSec7xSnapshotReader(backend, caches.keyPrefix).use { it.read(keysOf(data)) }
                conversion.problems.assert().isEmpty()
                conversion.contents.forEach { node.publisher.replace(it).block() }
                SnapshotAuthorizationFixture(node.store, options = OPTIONS).use { snapshot ->
                    val baseline = AuthorizationFactory.ENGINE.create(
                        GlobalByIdRepository(caches.policyRepository()),
                        caches.appRolePermissionRepository(),
                        BlacklistChecker.NoOp,
                        RootPrincipalMatcher.NONE,
                    )
                    compare(DecisionParity(baseline, snapshot.decisionAuthorization, FailureView.INNERMOST_CAUSE))
                }
            }
        }
    }

    /**
     * 7.0 reads the global policies in the order of a Redis set, which is not defined; a document lists them by id
     * (RFC 0003 §7 X3). Both sides read them by id, so the rule that decides is the same.
     */
    private class GlobalByIdRepository(private val delegate: PolicyRepository) : PolicyRepository by delegate {
        override fun getGlobalPolicy(): Mono<List<Policy>> = delegate.getGlobalPolicy().map { it.sortedBy(Policy::id) }
    }

    private fun Raw7xWriter.store(data: LegacyData) {
        data.policies.forEach { (id, json) -> policy(id, json) }
        if (data.globalIndex.isNotEmpty()) {
            index(*data.globalIndex.toTypedArray())
        }
        data.appPermissions.forEach { (appId, json) -> appPermission(appId, json) }
        data.grants.forEach { (appId, grants) ->
            grants.forEach { (key, json) -> grants(grantId(appId, key), json) }
        }
    }

    private fun keysOf(data: LegacyData) = CoSec7xReader.Keys(
        data.policies.keys,
        data.appPermissions.keys,
        data.grants.flatMap { (appId, grants) -> grants.keys.map { grantId(appId, it) } }.toSet(),
    )

    private fun grantId(appId: String, key: String): AppSpacedRoleId {
        val role = checkNotNull(RoleScope.parse(key))
        return AppSpacedRoleId(appId, role.roleId, role.spaceId)
    }

    companion object {
        private const val SEED = 20261026
        private val ITERATIONS = System.getProperty("cosec.parity.iterations")?.toInt() ?: 2_000
        private val THREADS = System.getProperty("cosec.parity.threads")?.toInt() ?: 1
        private const val BATCH = 1_000
        private val OPTIONS = SnapshotOptions(revalidateInterval = Duration.ZERO)
    }
}
