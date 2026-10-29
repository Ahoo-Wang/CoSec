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
import me.ahoo.cosec.runtime.Basis
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.Unavailability
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.runtime.shadow.Candidate
import me.ahoo.cosec.runtime.shadow.CandidateSource
import me.ahoo.cosec.runtime.shadow.ShadowOutcome
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
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
            // Break-glass reaches only CoSec's own tokens (RFC 0009 §4.2).
            AuthenticatedPrincipal(
                PrincipalId("cosec"),
                TenantId.PLATFORM,
                PipelineFixtures.claims(),
                credential = Identity.Credential(Identity.Method.JWT, "t1", null),
            ),
            "token"
        )
    }

    private fun evaluate(document: CompiledDocument, vararg listeners: DecisionListener): Outcome {
        val registered = DecisionListeners(listeners.toList()).also { opened += it }
        val pipeline = PipelineFixtures.pipeline(
            authenticator = operator,
            engine = CoSecFlatEngine,
            views = Views(document),
        ) { it.copy(listeners = registered) }
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
        val sink = me.ahoo.cosec.spi.audit.AuditEventSink { }
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

    /** Review A1 of #1070: a check writes no audit, so a check only break-glass permits is refused. */
    @Test
    fun aCheckThatOnlyBreakGlassPermitsIsRefused() {
        val audit = Recording("audit", Delivery.REQUIRED)
        val registered = DecisionListeners(listOf(audit)).also { opened += it }
        val pipeline = PipelineFixtures.pipeline(
            authenticator = operator,
            engine = CoSecFlatEngine,
            views = Views(breakGlass),
        ) { it.copy(listeners = registered) }
        val outcome = runBlocking { pipeline.evaluate(PipelineFixtures.facts()) }
        outcome.status.assert().isEqualTo(200)
        audit.events.size.assert().isEqualTo(1)
        val checker = me.ahoo.cosec.runtime.AuthorizationChecker(pipeline)
        runBlocking { checker.check(outcome.security!!.securityContext, "order:delete", "order/1") }.assert().isFalse()
        audit.events.size.assert().isEqualTo(1)
    }

    /** Review A2 of #1070: when another binding permits too, the request is not a use of break-glass. */
    @Test
    fun aRequestAnotherBindingPermitsIsNotBreakGlass() {
        val both = compile(
            ""","policies":[$allowAll],"bindings":[$anyone,{"id":"root","subject":{"principal":"cosec",""" +
                """"tenant":"(platform)"},"app":"*","space":"*","policy":"cosec:root","breakGlass":true,""" +
                """"expiresAt":"2099-12-31T00:00:00Z"}]"""
        )
        // No REQUIRED listener: a break-glass report would be a 503.
        evaluate(both).status.assert().isEqualTo(200)
        val audit = Recording("audit", Delivery.REQUIRED)
        evaluate(both, audit).status.assert().isEqualTo(200)
        audit.events.single().breakGlass.assert().isFalse()
    }

    @Test
    fun anOrdinaryPermitIsNotBreakGlass() {
        val audit = Recording("audit", Delivery.REQUIRED)
        evaluate(compile(""","policies":[$allowAll],"bindings":[$anyone]"""), audit).status.assert().isEqualTo(200)
        audit.events.single().breakGlass.assert().isFalse()
        evaluate(compile(""","policies":[$allowAll],"bindings":[$anyone]""")).status.assert().isEqualTo(200)
    }

    // --- shadow ------------------------------------------------------------------------------------------------

    private val recorder = ShadowRecorder("instance-1")

    /** The platform candidate of window `w`, made on [base] (the views install revision 7). */
    private fun candidate(document: CompiledDocument, base: Long = 7) = CandidateSource { key ->
        if (key == SnapshotKey.Platform) Candidate.Ready("w", base, document) else null
    }

    private fun counts(): Map<ShadowOutcome, Long> = runBlocking {
        withTimeout(5.seconds) {
            while (recorder.reports().isEmpty()) {
                delay(5)
            }
        }
        recorder.reports().single().counts
    }

    @Test
    fun aSampledRequestIsEvaluatedAgainstTheCandidates() {
        val enforced = compile(""","policies":[$allowAll],"bindings":[$anyone]""")
        val shadow = ShadowListener("shadow", 1.0, candidate(compile("")), recorder)
        evaluate(enforced, shadow).status.assert().isEqualTo(200)
        counts().assert().isEqualTo(mapOf(ShadowOutcome.NARROWED to 1L))
        val sample = recorder.reports().single().samples.getValue(ShadowOutcome.NARROWED).single()
        sample.principal.assert().isEqualTo("cosec")
        sample.candidateBy.assert().isNull()
        sample.enforcedBy!!.assert().startsWith("allow-all[0]")
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
        evaluate(enforced, ShadowListener("shadow", 1.0, candidate(candidate), recorder))
        counts().assert().isEqualTo(mapOf(ShadowOutcome.CANDIDATE_INDETERMINATE to 1L))
    }

    @Test
    fun aCandidateOnAnotherBaseIsNotCompared() {
        val enforced = compile(""","policies":[$allowAll],"bindings":[$anyone]""")
        evaluate(enforced, ShadowListener("shadow", 1.0, candidate(compile(""), base = 6), recorder))
        counts().assert().isEqualTo(mapOf(ShadowOutcome.BASE_MISMATCH to 1L))
        recorder.reports().single().samples.assert().isEmpty()
    }

    @Test
    fun aCandidateThatDoesNotCompileIsAnError() {
        val enforced = compile(""","policies":[$allowAll],"bindings":[$anyone]""")
        val failed = CandidateSource { key ->
            if (key == SnapshotKey.Platform) {
                Candidate.Failed(
                    "w",
                    IllegalStateException("x")
                )
            } else {
                null
            }
        }
        evaluate(enforced, ShadowListener("shadow", 1.0, failed, recorder)).status.assert().isEqualTo(200)
        counts().assert().isEqualTo(mapOf(ShadowOutcome.CANDIDATE_ERROR to 1L))
    }

    @Test
    fun theSameDecisionIsCountedWithoutASample() {
        val enforced = compile(""","policies":[$allowAll],"bindings":[$anyone]""")
        evaluate(enforced, ShadowListener("shadow", 1.0, candidate(enforced), recorder))
        counts().assert().isEqualTo(mapOf(ShadowOutcome.SAME to 1L))
        recorder.reports().single().samples.assert().isEmpty()
    }

    /** The replay handle keeps a view reachable: an unsampled request's event has none. */
    @Test
    fun anUnsampledRequestCarriesNoReplay() {
        val enforced = compile(""","policies":[$allowAll],"bindings":[$anyone]""")
        val probe = Recording("probe", Delivery.BEST_EFFORT)
        val shadow = ShadowListener("shadow", 0.0, { error("never asked") }, recorder)
        evaluate(enforced, shadow, probe)
        eventually({ probe.events }).single().replay.assert().isNull()
        val sampled = ShadowListener("sampled", 1.0, { null }, recorder)
        val sampledProbe = Recording("probe", Delivery.BEST_EFFORT)
        evaluate(enforced, sampled, sampledProbe)
        eventually({ sampledProbe.events }).single().replay!!.sampledBy.assert().containsExactly("sampled")
    }
}
