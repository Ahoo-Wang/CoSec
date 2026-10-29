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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.engine.flat.ViewVersion
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import me.ahoo.cosec.model.Scope as DeclaredRequestScope

/**
 * P7 (RFC 0003 §8.4, `SNP-1`): every evaluation reads one revision of each document. Every statement of a document is
 * named after the document's revision, a writer keeps publishing new revisions of the platform and tenant documents
 * while readers evaluate, and each fully traced evaluation must show only statements of the revisions of the view it
 * was given. Real implementations throughout: the in-memory store notifies the source, which recompiles on its own
 * threads with the 8.0 document compiler, and the flat engine evaluates.
 */
class SnapshotConsistencyPropertyTest {
    private fun document(revision: Long, body: String): SnapshotDocument {
        val publishedAt = Instant.parse("2026-10-27T00:00:00Z")
        val json = """{"schema":2,"revision":$revision,"publishedAt":"$publishedAt",$body}"""
        return SnapshotDocument(SnapshotDocument.DOCUMENT_SCHEMA, revision, publishedAt, json.encodeToByteArray())
    }

    private fun policy(id: String, revision: Long) =
        """{"id":"$id","statements":[${statements(id, revision)}]}"""

    private fun statements(policy: String, revision: Long) = (0 until STATEMENTS).joinToString(",") {
        val effect = if (it == 0) "deny" else "allow"
        """{"name":"$policy$it-r$revision","effect":"$effect","action":"http:NEVER"}"""
    }

    private fun binding(id: String, policy: String) =
        """{"id":"$id","subject":"*","app":"*","space":"*","policy":"$policy"}"""

    private fun platform(revision: Long) = document(
        revision,
        """"policies":[${policy("g", revision)},${policy("p", revision)}],""" +
            """"bindings":[${binding("g", "g")},${binding("p", "p")}]""",
    )

    private fun tenant(revision: Long) = document(
        revision,
        """"policies":[${policy("t", revision)}],"bindings":[${binding("t", "t")}]""",
    )

    private fun revisionOf(name: String): Long = name.substringAfterLast("-r").toLong()

    private val scope: Scope = run {
        val subject = ScopeSubject(TENANT, true, MEMBERSHIPS)
        val resolution = ScopeResolver.resolve(subject, DeclaredScope("", ""), HostScope(null))
        (resolution as ScopeResolution.Resolved).scope
    }

    private val request = FlatRequest(
        AuthzRequest(
            subject = Subject("user", TENANT.value, true, emptySet(), emptySet(), emptyMap(), false, true, true),
            scope = DeclaredRequestScope.declared(TENANT.value, "", ""),
            action = Action("http", "GET"),
            resource = Resource("http", "/x", emptyMap()),
            environment = Environment("127.0.0.1", "request", "", false),
        ),
        scope,
        MEMBERSHIPS,
        0,
    )

    /** The view [set] makes, as `DocumentSnapshotViews` makes it for a request that needs the tenant only. */
    private fun view(set: SnapshotSet<CompiledDocument, CompiledDocument>): SnapshotView {
        val tenant = set.entry(TENANT_KEY)!!
        return SnapshotView(
            platform = compiled(set.shared.state),
            app = null,
            tenant = compiled(tenant.state),
            version = ViewVersion(set.shared.revision, null, tenant.revision),
        )
    }

    private fun compiled(state: DocumentState<*>): CompiledDocument = when (state) {
        is DocumentState.Present<*> -> state.compiled as CompiledDocument
        is DocumentState.Failed -> throw AssertionError("A document failed to compile", state.error)
        DocumentState.Absent -> throw AssertionError("A document is absent")
    }

    /** The statements of [evaluation] that do not belong to the revisions of [view]. */
    private fun violationsOf(view: SnapshotView, evaluation: FlatEvaluation): List<String> {
        val violations = ArrayList<String>()
        val version = view.version!!
        if (evaluation.decision !is Decision.Deny) {
            violations.add("decided ${evaluation.decision} in $version")
        }
        // The built-in policies (the blacklist) belong to no document; every other statement came through a binding.
        val candidates = evaluation.trace!!.candidates.filterNot { it.via == BindingRef.Builtin }
        if (candidates.size != STATEMENTS_PER_EVALUATION) {
            violations.add("${candidates.size} statements in $version")
        }
        for (candidate in candidates) {
            val expected = when ((candidate.via as? BindingRef.Bound)?.document) {
                DocumentKind.PLATFORM -> version.platform
                DocumentKind.TENANT -> version.tenant
                else -> null
            }
            if (expected == null || revisionOf(candidate.ref.name) != expected) {
                violations.add("${candidate.ref.name} via ${candidate.via} in $version")
            }
        }
        return violations
    }

    @Suppress("TooGenericExceptionCaught")
    @Test
    fun everyEvaluationReadsOneRevisionPerDocument() {
        val store = InMemorySnapshotDocumentStore()
        store.put(SnapshotKey.Platform, platform(1))
        store.put(TENANT_KEY, tenant(1))
        val compiler = PolicyDocumentCompiler.of(ConditionLimits.DEFAULT, AttributeCatalog.NONE)
        val violations = CopyOnWriteArrayList<String>()
        val seen = ConcurrentHashMap.newKeySet<Pair<Long, Long?>>()
        val evaluations = AtomicInteger()
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO)
        DocumentSnapshots.source(store, compiler, options).use { source ->
            val readers = Executors.newFixedThreadPool(READERS)
            val done = CountDownLatch(READERS)
            val publishing = AtomicBoolean(true)
            repeat(READERS) {
                readers.execute {
                    try {
                        while (publishing.get()) {
                            val view = view(source.loadAll(listOf(TENANT_KEY)).get())
                            val evaluation = CoSecFlatEngine.evaluate(request, view, TraceLevel.FULL)
                            evaluations.incrementAndGet()
                            seen.add(view.version!!.platform to view.version!!.tenant)
                            violations.addAll(violationsOf(view, evaluation))
                        }
                    } catch (error: Throwable) {
                        violations.add(generateSequence<Throwable>(error) { it.cause }.joinToString(" <- "))
                    } finally {
                        done.countDown()
                    }
                }
            }
            for (revision in 2L..revisions) {
                store.put(SnapshotKey.Platform, platform(revision))
                store.put(TENANT_KEY, tenant(revision))
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
        evaluations.get().assert().isGreaterThan(revisions.toInt())
        // The readers must have evaluated against many distinct (platform, tenant) revision pairs, or nothing was tested.
        seen.size.assert().isGreaterThan(10)
    }

    companion object {
        private const val READERS = 4
        private const val STATEMENTS = 3
        private val TENANT = TenantId("acme")
        private val TENANT_KEY = SnapshotKey.Tenant(TENANT.value)
        private val MEMBERSHIPS = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())

        /**
         * Revisions published while the readers evaluate. propertyStress raises it (RFC 0008 §3.5): each revision is a
         * publish and a recompile of both documents, so one per thousand of its cases.
         */
        private val revisions: Long = maxOf(200L, Integer.getInteger("cosec.property.iterations", 0) / 1000L)

        /** Three statements in each of the two platform policies and in the tenant's. */
        private const val STATEMENTS_PER_EVALUATION = 3 * STATEMENTS
    }
}
