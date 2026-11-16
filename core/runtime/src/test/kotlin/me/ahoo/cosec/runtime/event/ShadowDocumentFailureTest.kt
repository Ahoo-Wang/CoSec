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

package me.ahoo.cosec.runtime.event

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.shadow.Candidate
import me.ahoo.cosec.runtime.shadow.CandidateSource
import me.ahoo.cosec.runtime.shadow.ShadowListener
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshotViews
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.runtime.snapshot.QUIET_PASS
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

/**
 * #1410: a tenant candidate is compared on the documents it reads, and so on their failures only. alice has no role in
 * `order`; her tenant permits `http:GET` on its own, and also binds order's permission policy to `Anyone`, so her request
 * reads order's document (RC1-17). When that document cannot be read or does not compile, the request is 503. A
 * candidate that drops the binding no longer reads it: once published the request is 200, so the shadow reports
 * `WIDENED`, never `SAME` on the failure of a document the candidate does not read. The failures of documents it still
 * reads, or newly reads, stay.
 *
 * A candidate of the document that does not compile replaces it: the candidate is that document, compiled, so the stored
 * one's failure goes. One that could not be read is not compared, since the shadow cannot know the store will answer.
 */
class ShadowDocumentFailureTest {
    /** How order's document fails, when it does. */
    enum class Failure {
        /** It is stored and does not compile: an unknown member. */
        INVALID,

        /** It is valid, and its read fails. */
        UNREADABLE,
    }

    private val store = InMemorySnapshotDocumentStore()

    /** The documents whose reads fail. */
    private val unreadable: MutableSet<SnapshotKey> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val reading = object : SnapshotDocumentStore by store {
        override fun read(key: SnapshotKey): CompletableFuture<SnapshotRead> = failing(key) ?: store.read(key)

        override fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?): CompletableFuture<SnapshotRead> =
            failing(key) ?: store.readIfChanged(key, known)
    }

    /** A failed read of a document that is [unreadable]; null otherwise. */
    private fun failing(key: SnapshotKey): CompletableFuture<SnapshotRead>? {
        if (key !in unreadable) {
            return null
        }
        return CompletableFuture.failedFuture(IllegalStateException("down"))
    }

    private val compiler = PolicyDocumentCompiler()
    private val source = DocumentSnapshots.source(reading, compiler, SnapshotOptions(revalidateInterval = QUIET_PASS))
    private val views = DocumentSnapshotViews(source)
    private val recorder = ShadowRecorder("instance-1")
    private val opened = ArrayList<DecisionListeners>()

    @AfterEach
    fun close() {
        opened.forEach { it.cancel() }
        source.close()
    }

    private fun document(body: String, revision: Long = 1): SnapshotDocument {
        val text = """{"schema":2,"revision":$revision,"publishedAt":"2026-10-26T00:00:00Z"$body}"""
        return SnapshotDocuments.read(text.encodeToByteArray())
    }

    private fun tenant(vararg bindings: String, revision: Long = 1) =
        document(""","policies":[$OWN],"bindings":[${bindings.joinToString(",")}]""", revision)

    private fun install(app: Failure?, vararg tenantBindings: String) {
        store.put(SnapshotKey.Platform, document(""))
        val invalid = if (app == Failure.INVALID) ""","unknown":1""" else ""
        store.put(ORDER, document(""","policies":[$READ]$invalid"""))
        if (app == Failure.UNREADABLE) unreadable += ORDER
        store.put(TENANT, tenant(*tenantBindings))
    }

    /** The tenant candidate of window `w`, made on the installed revision 1, with [bindings]. */
    private fun candidate(vararg bindings: String): ShadowListener {
        val document = compiler.compile(TENANT, tenant(*bindings))
        val candidates = CandidateSource { key -> if (key == TENANT) Candidate.Ready("w", 1, document) else null }
        return ShadowListener("shadow", 1.0, candidates, recorder, views = views)
    }

    /** The candidate of [key] of window `w`, made on the installed revision 1. */
    private fun replacing(key: SnapshotKey, body: String): ShadowListener {
        val document = compiler.compile(key, document(body))
        val candidates = CandidateSource { asked -> if (asked == key) Candidate.Ready("w", 1, document) else null }
        return ShadowListener("shadow", 1.0, candidates, recorder, views = views)
    }

    /** alice, roleless, reads `/orders` in the order app. */
    private fun evaluate(shadow: ShadowListener? = null): Int {
        val registered = DecisionListeners(listOfNotNull(shadow)).also { opened += it }
        val pipeline = PipelineFixtures.pipeline(
            authenticator = PipelineFixtures.authenticator(PipelineFixtures.authenticated(app = "order")),
            engine = CoSecFlatEngine,
            views = views,
        ) { listeners(registered) }
        return runBlocking {
            pipeline.evaluate(PipelineFixtures.facts(host = HostScope(AppId("order")))).status
        }
    }

    private fun counts(): Map<ShadowOutcome, Long> = runBlocking {
        withTimeout(5.seconds) {
            while (recorder.reports().isEmpty()) {
                delay(5)
            }
        }
        recorder.reports().single().counts
    }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `a candidate that drops a failed app dependency predicts the availability it restores`(failure: Failure) {
        install(failure, SELF, GRANT)
        evaluate(candidate(SELF)).assert().isEqualTo(503)
        counts().assert().isEqualTo(mapOf(ShadowOutcome.WIDENED to 1L))
        val sample = recorder.reports().single().samples.getValue(ShadowOutcome.WIDENED).single()
        sample.candidateBy!!.assert().startsWith("own")

        // What the shadow predicted is what the candidate does once published.
        store.put(TENANT, tenant(SELF, revision = 2))
        source.reload(TENANT).join().revision.assert().isEqualTo(2)
        evaluate().assert().isEqualTo(200)
    }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `a candidate that still reads the failed app document is indeterminate as the live view is`(failure: Failure) {
        install(failure, SELF, GRANT)
        evaluate(candidate(SELF, GRANT, ALSO)).assert().isEqualTo(503)
        counts().assert().isEqualTo(mapOf(ShadowOutcome.SAME to 1L))
    }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `a candidate that starts reading a failed app document carries its failure`(failure: Failure) {
        install(failure, SELF)
        evaluate(candidate(SELF, GRANT)).assert().isEqualTo(200)
        counts().assert().isEqualTo(mapOf(ShadowOutcome.BECAME_INDETERMINATE to 1L))
        val sample = recorder.reports().single().samples.getValue(ShadowOutcome.BECAME_INDETERMINATE).single()
        val cause = if (failure == Failure.INVALID) "DocumentInvalid" else "SourceUnavailable"
        sample.candidateBy.assert().isEqualTo(cause)
    }

    @Test
    fun `with a healthy app document, dropping the dependency changes nothing`() {
        install(null, SELF, GRANT)
        // The tenant's own ALLOW decides both, before the app's binding (CandidateOrder).
        evaluate(candidate(SELF)).assert().isEqualTo(200)
        counts().assert().isEqualTo(mapOf(ShadowOutcome.SAME to 1L))
    }

    /** Only the app document's failure goes with it: the platform's, which every request reads, stays. */
    @Test
    fun `the failure of a document the candidate still reads is kept`() {
        install(Failure.INVALID, SELF, GRANT)
        store.put(SnapshotKey.Platform, document(""","unknown":1"""))
        source.reload(SnapshotKey.Platform).join().invalid.assert().isTrue()
        evaluate(candidate(SELF)).assert().isEqualTo(503)
        counts().assert().isEqualTo(mapOf(ShadowOutcome.SAME to 1L))
    }

    /** A platform candidate that compiles, replacing one that does not: the request it unblocks is `WIDENED`. */
    @Test
    fun `a platform candidate that compiles replaces the failure of the platform that does not`() {
        install(null, SELF)
        store.put(SnapshotKey.Platform, document(""","unknown":1"""))
        source.reload(SnapshotKey.Platform).join().invalid.assert().isTrue()
        evaluate(replacing(SnapshotKey.Platform, "")).assert().isEqualTo(503)
        counts().assert().isEqualTo(mapOf(ShadowOutcome.WIDENED to 1L))

        store.put(SnapshotKey.Platform, document("", revision = 2))
        source.reload(SnapshotKey.Platform).join().revision.assert().isEqualTo(2)
        evaluate().assert().isEqualTo(200)
    }

    /**
     * An app candidate of the document the request reads: it replaces one that does not compile (`WIDENED`). One that
     * could not be read has no revision the view knows (0), so the candidate is not compared on it: `BASE_MISMATCH`, never
     * a prediction that the store will answer.
     */
    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `an app candidate replaces an app document that does not compile, and is not compared on one that cannot be read`(
        failure: Failure,
    ) {
        install(failure, GRANT)
        evaluate(replacing(ORDER, ""","policies":[$READ]""")).assert().isEqualTo(503)
        val expected = if (failure == Failure.INVALID) ShadowOutcome.WIDENED else ShadowOutcome.BASE_MISMATCH
        counts().assert().isEqualTo(mapOf(expected to 1L))
    }

    /**
     * A tenant candidate of the tenant document the request reads: it replaces one that does not compile (`WIDENED`),
     * and is not compared on one that cannot be read (`BASE_MISMATCH`), as for the app document.
     */
    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `a tenant candidate replaces a tenant document that does not compile, and is not compared on one that cannot be read`(
        failure: Failure,
    ) {
        install(null)
        if (failure == Failure.INVALID) {
            store.put(TENANT, document(""","policies":[$OWN],"bindings":[$SELF],"unknown":1"""))
        } else {
            unreadable += TENANT
        }
        source.reload(TENANT).join()
        evaluate(candidate(SELF)).assert().isEqualTo(503)
        val expected = if (failure == Failure.INVALID) ShadowOutcome.WIDENED else ShadowOutcome.BASE_MISMATCH
        counts().assert().isEqualTo(mapOf(expected to 1L))
    }

    private companion object {
        val ORDER = SnapshotKey.App("order")
        val TENANT = SnapshotKey.Tenant(PipelineFixtures.TENANT.value)
        const val READ = """{"id":"perm:order/read","statements":[{"effect":"allow","action":"http:GET"}]}"""
        const val OWN = """{"id":"own","statements":[{"effect":"allow","action":"http:GET"}]}"""
        const val SELF = """{"id":"self","subject":"*","app":"*","space":"*","policy":"own"}"""
        const val GRANT = """{"id":"reads","subject":"*","app":"order","space":"*","policy":"perm:order/read"}"""
        const val ALSO = """{"id":"reads-too","subject":"*","app":"order","space":"*","policy":"perm:order/read"}"""
    }
}
