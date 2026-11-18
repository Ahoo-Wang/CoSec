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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.document.shadow.ShadowCodec
import me.ahoo.cosec.engine.CoSecEngine
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.shadow.CachingCandidateSource
import me.ahoo.cosec.runtime.shadow.ShadowListener
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshotViews
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.runtime.snapshot.QUIET_PASS
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * SNP-12, one shape of the candidate record: a record 8.0.0-alpha.4 staged states `schema`, `revision` and
 * `publishedAt` beside its document. It is refused as an unreadable candidate is: the instance compares no window on
 * it, installs nothing, and the request it would have been compared on is answered as it is without a shadow.
 */
class CandidateRecordShapeTest {
    private val store = InMemorySnapshotDocumentStore()
    private val compiler = PolicyDocumentCompiler()
    private val source = DocumentSnapshots.source(store, compiler, SnapshotOptions(revalidateInterval = QUIET_PASS))
    private val views = DocumentSnapshotViews(source)
    private val opened = ArrayList<DecisionListeners>()

    @AfterEach
    fun close() {
        opened.forEach { it.cancel() }
        source.close()
    }

    private fun document(body: String) = SnapshotDocuments.read(
        """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"$body}""".encodeToByteArray()
    )

    private fun evaluate(shadow: ShadowListener?): Int {
        val registered = DecisionListeners(listOfNotNull(shadow)).also { opened += it }
        val pipeline = PipelineFixtures.pipeline(
            authenticator = PipelineFixtures.authenticator(PipelineFixtures.authenticated(app = "order")),
            engine = CoSecEngine,
            views = views,
        ) { listeners(registered) }
        return runBlocking { pipeline.evaluate(PipelineFixtures.facts(host = HostScope(AppId("order")))).status }
    }

    @Test
    fun `a candidate record in the shape of 8_0_0-alpha_4 is not compared, and the request is answered as without it`() {
        store.put(SnapshotKey.Platform, document(""))
        store.put(TENANT, document(""","policies":[$OWN],"bindings":[$SELF]"""))
        val alpha4 = """{"window":"w","baseRevision":1,"schema":2,"revision":2,"publishedAt":"2026-10-27T00:00:00Z",""" +
            """"document":{"schema":2,"revision":2,"publishedAt":"2026-10-27T00:00:00Z"}}"""
        val candidates = CachingCandidateSource(
            { key -> if (key == TENANT) ShadowCodec.decodeCandidate(alpha4.encodeToByteArray()) else null },
            { key, stored -> compiler.compile(key, stored) },
        )
        val recorder = ShadowRecorder("instance-1")
        val without = evaluate(null)

        evaluate(ShadowListener("shadow", 1.0, candidates, recorder, views = views)).assert().isEqualTo(without)

        without.assert().isEqualTo(200)
        runBlocking { candidates.candidate(TENANT) }.assert().isNull()
        candidates.failures.assert().isGreaterThanOrEqualTo(1)
        recorder.reports().assert().isEmpty()
        source.entries().map { it.revision }.assert().doesNotContain(2L)
    }

    private companion object {
        val TENANT = SnapshotKey.Tenant(PipelineFixtures.TENANT.value)
        const val OWN = """{"id":"own","statements":[{"effect":"allow","action":"http:GET"}]}"""
        const val SELF = """{"id":"self","subject":"*","app":"*","space":"*","policy":"own"}"""
    }
}
