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

package me.ahoo.cosec.authorization

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.authorization.engine.PolicySnapshot
import me.ahoo.cosec.authorization.engine.PolicySnapshotCompiler
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.model.StatementRef
import me.ahoo.cosec.parity.appPermissionJson
import me.ahoo.cosec.parity.policyJson
import me.ahoo.cosec.parity.snapshotDocument
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotOptions
import me.ahoo.cosec.snapshot.SnapshotSource
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * P7 (RFC 0003 §8.4): every evaluation reads one revision of each document. Every rule of a document is named after
 * the document's revision, a writer keeps publishing new revisions of both documents while readers evaluate, and each
 * traced evaluation must show only rules of the revisions of the snapshot it was given. Real implementations
 * throughout: the in-memory store notifies the source, which recompiles on its own threads.
 */
class SnapshotConsistencyPropertyTest {
    private fun policies(revision: Long): SnapshotDocument {
        val global = policyJson("g", "global", rules("g", revision, 3))
        val custom = policyJson("p", "custom", rules("p", revision, 3))
        return snapshotDocument(revision, """"policies":[$global,$custom]""")
    }

    private fun app(revision: Long): SnapshotDocument {
        val permissions = (0 until 3).joinToString(",") {
            """{"id":"perm$it","name":"perm$it-r$revision","description":"","effect":"allow","action":"/never"}"""
        }
        return snapshotDocument(
            revision,
            """"appPermission":${appPermissionJson("app", permissions)},"grants":{"r1":["perm0","perm1"],"r2":["*"]}""",
        )
    }

    private fun rules(policy: String, revision: Long, count: Int) = (0 until count).joinToString(",") {
        """{"name":"$policy$it-r$revision","effect":"${if (it == 0) "deny" else "allow"}","action":"/never"}"""
    }

    private fun revisionOf(name: String): Long = name.substringAfterLast("-r").toLong()

    /** The rules of [evaluation] that do not belong to the revisions of [snapshot]. */
    private fun violationsOf(snapshot: PolicySnapshot, evaluation: Evaluation): List<String> {
        val violations = ArrayList<String>()
        val trace = evaluation.trace!!
        if (trace.layers.map { it.kind } != LayerKind.entries.toList()) {
            violations.add("layers ${trace.layers.map { it.kind }} in snapshot ${snapshot.version}")
        }
        val rules = trace.layers.flatMap { it.groups }.flatMap { it.rules }
        if (rules.size != RULES_PER_EVALUATION) {
            violations.add("${rules.size} rules in snapshot ${snapshot.version}")
        }
        for (rule in rules) {
            val (expected, name) = when (val ref = rule.ref) {
                is StatementRef.PolicyStatement -> snapshot.policiesEntry.revision to ref.name
                is StatementRef.RolePermission -> snapshot.appEntry!!.revision to ref.name
                else -> error("Unexpected $ref")
            }
            if (revisionOf(name) != expected) {
                violations.add("$name in snapshot ${snapshot.version}")
            }
        }
        return violations
    }

    @Suppress("TooGenericExceptionCaught")
    @Test
    fun everyEvaluationReadsOneRevisionPerDocument() {
        val store = InMemorySnapshotDocumentStore()
        store.put(SnapshotKey.Policies, policies(1))
        store.put(SnapshotKey.App("app"), app(1))
        val compiler = PolicySnapshotCompiler()
        val violations = CopyOnWriteArrayList<String>()
        val seen = ConcurrentHashMap.newKeySet<Pair<Long, Long>>()
        val evaluations = AtomicInteger()
        val check = { snapshot: PolicySnapshot, evaluation: Evaluation ->
            evaluations.incrementAndGet()
            seen.add(snapshot.policiesEntry.revision to snapshot.appEntry!!.revision)
            violations.addAll(violationsOf(snapshot, evaluation))
            Unit
        }
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO)
        SnapshotSource(store, compiler.policies, compiler.app, options).use { source ->
            val authorization = SnapshotEngineAuthorization(source, trace = TraceLevel.FULL, onEvaluated = check)
            val readers = Executors.newFixedThreadPool(READERS)
            val done = CountDownLatch(READERS)
            val publishing = AtomicBoolean(true)
            repeat(READERS) {
                readers.execute {
                    try {
                        while (publishing.get()) {
                            val context = SimpleSecurityContext(SimplePrincipal("user", setOf("p"), setOf("r1", "r2")))
                            val request =
                                EvaluateRequest(path = "/x", headers = mapOf(AppIdCapable.APP_ID_KEY to "app"))
                            val result = authorization.decide(request, context).block()!!.result
                            if (result != AuthorizeResult.IMPLICIT_DENY) violations.add("decided $result")
                        }
                    } catch (error: Throwable) {
                        violations.add(error.toString())
                    } finally {
                        done.countDown()
                    }
                }
            }
            for (revision in 2L..REVISIONS) {
                store.put(SnapshotKey.Policies, policies(revision))
                store.put(SnapshotKey.App("app"), app(revision))
                // Let the readers evaluate between revisions, however loaded the machine is.
                val target = evaluations.get() + READERS
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (evaluations.get() < target && System.nanoTime() < deadline && violations.isEmpty()) {
                    Thread.onSpinWait()
                }
            }
            publishing.set(false)
            done.await(30, TimeUnit.SECONDS).assert().isTrue()
            readers.shutdown()
        }
        violations.assert().isEmpty()
        evaluations.get().assert().isGreaterThan(REVISIONS.toInt())
        // The readers must have evaluated against many distinct (policies, app) revision pairs, or nothing was tested.
        seen.size.assert().isGreaterThan(10)
    }

    companion object {
        private const val READERS = 4
        private const val REVISIONS = 200L

        /** Three statements in each of two policies, two grants and a wildcard over three permissions. */
        private const val RULES_PER_EVALUATION = 3 + 3 + 2 + 3
    }
}
