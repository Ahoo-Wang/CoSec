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

package me.ahoo.cosec.runtime.event

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.engine.flat.ViewVersion
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.runtime.Basis
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.Unavailability
import me.ahoo.cosec.runtime.authn.AuthenticatedPrincipal
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.shadow.ShadowDiffKind
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDocumentCompiler
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** RFC 0006 §9.4.3 (break-glass needs a REQUIRED audit) and §9.5.4 (shadow evaluation), with the real engine. */
class BreakGlassAndShadowTest {
    private val opened = ArrayList<DecisionListeners>()

    @AfterEach
    fun close() {
        opened.forEach { it.cancel() }
    }

    private class Recording(override val name: String, override val delivery: Delivery) : DecisionListener {
        val events = CopyOnWriteArrayList<DecisionEvent>()

        override suspend fun onDecision(event: DecisionEvent) {
            events += event
        }
    }

    private class Views(val platform: CompiledDocument) : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            platform,
            key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
            version = ViewVersion(7, null, 0),
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    private fun compile(body: String, catalog: AttributeCatalog = AttributeCatalog.NONE): CompiledDocument =
        PolicyDocumentCompiler(catalog).compile(
            SnapshotKey.Platform,
            SnapshotDocument(
                2,
                1,
                Instant.parse("2026-10-26T00:00:00Z"),
                """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"$body}""".encodeToByteArray(),
            ),
        )

    private val breakGlass = compile(
        ""","bindings":[{"id":"root","subject":{"principal":"cosec","tenant":"(platform)"},"app":"*","space":"*",""" +
            """"policy":"cosec:root","breakGlass":true,"expiresAt":"2099-12-31T00:00:00Z"}]"""
    )

    private val allowAll = """{"id":"allow-all","statements":[{"effect":"allow","action":"*"}]}"""
    private val anyone = """{"id":"g","subject":"*","app":"*","space":"*","policy":"allow-all"}"""

    private val operator = Authenticator {
        AuthnResult.Authenticated(
            AuthenticatedPrincipal(PrincipalId("cosec"), TenantId.PLATFORM, PipelineFixtures.claims()),
            "token"
        )
    }

    private fun evaluate(document: CompiledDocument, vararg listeners: DecisionListener): Outcome {
        val registered = DecisionListeners(listeners.toList()).also { opened += it }
        val pipeline = PipelineFixtures.pipeline(
            authenticator = operator,
            engine = CoSecFlatEngine,
            views = Views(document),
            listeners = registered,
        )
        return runBlocking { pipeline.evaluate(PipelineFixtures.facts()) }
    }

    private fun <T> eventually(read: () -> List<T>, count: Int = 1): List<T> = runBlocking {
        withTimeout(5.seconds) {
            while (read().size < count) {
                delay(5)
            }
        }
        read()
    }

    // --- break-glass -------------------------------------------------------------------------------------------

    @Test
    fun aBreakGlassPermitWithoutARequiredListenerIs503() {
        val outcome = evaluate(breakGlass)
        outcome.status.assert().isEqualTo(503)
        ((outcome.basis as Basis.Unavailable).reason as Unavailability.Indeterminate).cause
            .assert().isEqualTo(Cause.RequiredListenerFailed(emptyList()))
        // Best-effort listeners alone do not count.
        evaluate(breakGlass, Recording("metrics", Delivery.BEST_EFFORT)).status.assert().isEqualTo(503)
    }

    /** Review R1: a 7.x audit sink cannot acknowledge a write, so it never counts as the REQUIRED audit. */
    @Test
    fun aBreakGlassPermitAuditedOnlyByASinkIs503() {
        val sink = me.ahoo.cosec.api.audit.AuditEventSink { }
        val audit = me.ahoo.cosec.runtime.audit.AuditListener.of(sink, "audit")
        audit.delivery.assert().isEqualTo(Delivery.BEST_EFFORT)
        evaluate(breakGlass, audit).status.assert().isEqualTo(503)
    }

    @Test
    fun aBreakGlassPermitWrittenToARequiredListenerProceeds() {
        val audit = Recording("audit", Delivery.REQUIRED)
        evaluate(breakGlass, audit).status.assert().isEqualTo(200)
        val event = audit.events.single()
        event.breakGlass.assert().isTrue()
        event.snapshotVersion.assert().isEqualTo(ViewVersion(7, null, 0))
    }

    @Test
    fun aBreakGlassPermitWhoseAuditFailsIs503() {
        val failing = object : DecisionListener {
            override val name = "audit"
            override val delivery = Delivery.REQUIRED

            override suspend fun onDecision(event: DecisionEvent) = error("audit store down")
        }
        evaluate(breakGlass, failing).status.assert().isEqualTo(503)
    }

    @Test
    fun anOrdinaryPermitIsNotBreakGlass() {
        val audit = Recording("audit", Delivery.REQUIRED)
        evaluate(compile(""","policies":[$allowAll],"bindings":[$anyone]"""), audit).status.assert().isEqualTo(200)
        audit.events.single().breakGlass.assert().isFalse()
        evaluate(compile(""","policies":[$allowAll],"bindings":[$anyone]""")).status.assert().isEqualTo(200)
    }

    // --- shadow ------------------------------------------------------------------------------------------------

    private class Diffs : ShadowDecisionSink {
        val diffs = CopyOnWriteArrayList<ShadowDecisionDiff>()

        override fun publish(diff: ShadowDecisionDiff) {
            diffs += diff
        }
    }

    @Test
    fun aSampledRequestIsEvaluatedAgainstTheCandidates() {
        val enforced = compile(""","policies":[$allowAll],"bindings":[$anyone]""")
        val candidate = compile("")
        val diffs = Diffs()
        val shadow = ShadowListener("shadow", 1.0, { view -> SnapshotView(candidate, view.app, view.tenant) }, diffs)
        evaluate(enforced, shadow).status.assert().isEqualTo(200)
        val diff = eventually({ diffs.diffs }).single()
        diff.kind.assert().isEqualTo(ShadowDiffKind.NARROWED)
        diff.candidate.assert().isEqualTo(Decision.Deny(null))
        diff.principal.assert().isEqualTo("cosec")
    }

    @Test
    fun aCandidateReadingAnAttributeTheRequestDidNotFetchIsIndeterminate() {
        val enforced = compile(""","policies":[$allowAll],"bindings":[$anyone]""")
        val catalog = AttributeCatalog.of(listOf(Attribute.external("ext.geo.inFence", AttributeType.BOOL)))
        val candidate = compile(
            ""","policies":[{"id":"allow-all","statements":[{"effect":"allow","action":"*",""" +
                """"condition":{"eq":["ext.geo.inFence",true]}}]}],"bindings":[$anyone]""",
            catalog,
        )
        val diffs = Diffs()
        val shadow = ShadowListener("shadow", 1.0, { view -> SnapshotView(candidate, view.app, view.tenant) }, diffs)
        evaluate(enforced, shadow)
        eventually({ diffs.diffs }).single().kind.assert().isEqualTo(ShadowDiffKind.CANDIDATE_INDETERMINATE)
    }

    /** The replay handle keeps a view reachable: an unsampled request's event has none. */
    @Test
    fun anUnsampledRequestCarriesNoReplay() {
        val enforced = compile(""","policies":[$allowAll],"bindings":[$anyone]""")
        val probe = Recording("probe", Delivery.BEST_EFFORT)
        val shadow = ShadowListener("shadow", 0.0, { error("never asked") }, Diffs())
        evaluate(enforced, shadow, probe)
        eventually({ probe.events }).single().replay.assert().isNull()
        val sampled = ShadowListener("sampled", 1.0, { null }, Diffs())
        val sampledProbe = Recording("probe", Delivery.BEST_EFFORT)
        evaluate(enforced, sampled, sampledProbe)
        eventually({ sampledProbe.events }).single().replay!!.sampledBy.assert().containsExactly("sampled")
    }
}
