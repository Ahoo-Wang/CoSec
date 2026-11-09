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

package me.ahoo.cosec.runtime.event

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.runtime.shadow.Candidate
import me.ahoo.cosec.runtime.shadow.CandidateSource
import me.ahoo.cosec.runtime.shadow.ShadowOutcome
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshotViews
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.runtime.snapshot.QUIET_PASS
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * CTL-3, R1-1 of #1194's review: a shadow window compares a tenant candidate on the documents enforcement would read
 * once it is published, by the one rule (`ViewDocuments`). alice has no role in `order`; a candidate of her tenant that
 * binds order's permission policy to `Anyone`, or to her, makes her request read order's document (RC1-17), so it
 * permits what the installed tenant denies: `WIDENED`, never `BECAME_INDETERMINATE`.
 */
class ShadowAppDocumentTest {
    private val store = InMemorySnapshotDocumentStore()
    private val compiler = PolicyDocumentCompiler()
    private val source = DocumentSnapshots.source(store, compiler, SnapshotOptions(revalidateInterval = QUIET_PASS))
    private val views = DocumentSnapshotViews(source)
    private val recorder = ShadowRecorder("instance-1")
    private val opened = ArrayList<DecisionListeners>()

    @AfterEach
    fun close() {
        opened.forEach { it.cancel() }
        source.close()
    }

    private fun document(body: String): SnapshotDocument {
        val text = """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"$body}"""
        return SnapshotDocument(2, 1, Instant.parse("2026-10-26T00:00:00Z"), text.encodeToByteArray())
    }

    private fun install(tenantBindings: String) {
        store.put(SnapshotKey.Platform, document(""))
        store.put(ORDER, document(""","policies":[$READ]"""))
        store.put(TENANT, document(""","bindings":[$tenantBindings]"""))
    }

    private fun compiled(bindings: String): CompiledDocument =
        compiler.compile(TENANT, document(""","bindings":[$bindings]"""))

    /** The tenant candidate of window `w`, made on the installed revision 1. */
    private fun candidate(document: CompiledDocument) = CandidateSource { key ->
        if (key == TENANT) Candidate.Ready("w", 1, document) else null
    }

    /** alice, roleless, reads `/orders` in the order app. */
    private fun evaluate(shadow: ShadowListener, viewsOf: SnapshotViews = views): Int {
        val registered = DecisionListeners(listOf(shadow)).also { opened += it }
        val pipeline = PipelineFixtures.pipeline(
            authenticator = PipelineFixtures.authenticator(PipelineFixtures.authenticated(app = "order")),
            engine = CoSecFlatEngine,
            views = viewsOf,
        ) { it.copy(listeners = registered) }
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

    private fun grant(subject: String): String {
        val who = if (subject == "anyone") "\"*\"" else """{"principal":"${PipelineFixtures.PRINCIPAL.value}"}"""
        return """{"id":"reads","subject":$who,"app":"order","space":"*","policy":"perm:order/read"}"""
    }

    @ParameterizedTest
    @ValueSource(strings = ["anyone", "alice"])
    fun `a tenant candidate that grants an app permission reads the app document and widens`(subject: String) {
        install("")
        val shadow = ShadowListener("shadow", 1.0, candidate(compiled(grant(subject))), recorder, views = views)
        evaluate(shadow).assert().isEqualTo(403)
        counts().assert().isEqualTo(mapOf(ShadowOutcome.WIDENED to 1L))
        val sample = recorder.reports().single().samples.getValue(ShadowOutcome.WIDENED).single()
        sample.candidateBy!!.assert().startsWith("perm:order/read")
    }

    @ParameterizedTest
    @ValueSource(strings = ["anyone", "alice"])
    fun `a tenant candidate that revokes it reads no app document and narrows`(subject: String) {
        install(grant(subject))
        val shadow = ShadowListener("shadow", 1.0, candidate(compiled("")), recorder, views = views)
        evaluate(shadow).assert().isEqualTo(200)
        counts().assert().isEqualTo(mapOf(ShadowOutcome.NARROWED to 1L))
        val sample = recorder.reports().single().samples.getValue(ShadowOutcome.NARROWED).single()
        sample.candidateBy.assert().isNull()
    }

    /** Without the installed documents the app document cannot be read: never a comparison on fewer documents. */
    @Test
    fun withoutViewsSuchACandidateIsAnError() {
        install("")
        val shadow = ShadowListener("shadow", 1.0, candidate(compiled(grant("anyone"))), recorder)
        evaluate(shadow).assert().isEqualTo(403)
        counts().assert().isEqualTo(mapOf(ShadowOutcome.CANDIDATE_ERROR to 1L))
    }

    private companion object {
        val ORDER = SnapshotKey.App("order")
        val TENANT = SnapshotKey.Tenant(PipelineFixtures.TENANT.value)
        const val READ = """{"id":"perm:order/read","statements":[{"effect":"allow","action":"http:GET"}]}"""
    }
}
