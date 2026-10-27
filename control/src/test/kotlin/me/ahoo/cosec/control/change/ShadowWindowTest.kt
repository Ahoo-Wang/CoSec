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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.engine.flat.ViewVersion
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.authn.AuthenticatedPrincipal
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.ShadowListener
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.shadow.CachingCandidateSource
import me.ahoo.cosec.runtime.shadow.InMemoryShadowReports
import me.ahoo.cosec.runtime.shadow.InstalledDocument
import me.ahoo.cosec.runtime.shadow.ShadowOutcome
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.runtime.shadow.ShadowReporter
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDefinition
import me.ahoo.cosec.snapshot.document.PolicyDocumentCompiler
import me.ahoo.cosec.snapshot.document.ProvenanceKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * RFC 0007 §6.4, §6.5 end to end: two data-plane instances over one in-memory control store sample their requests
 * against a staged candidate, report what they saw, and the control plane closes the window from the reports. The
 * candidate never decides a request.
 */
class ShadowWindowTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)
    private val reports = InMemoryShadowReports(clock)
    private val ids = AtomicInteger()
    private val opened = ArrayList<DecisionListeners>()

    private val options =
        ShadowOptions(minDuration = Duration.ofMinutes(1), minSamples = 4, maxDuration = Duration.ofMinutes(10))
    private val changes = Changes(
        store,
        revisions,
        { ChangeId("c${ids.incrementAndGet()}") },
        PERMIT_ALL,
        clock,
        ChangeOptions(shadow = options),
        reports = reports,
        // Break-glass is recorded by a REQUIRED listener first (RFC 0007 §8).
        listeners = me.ahoo.cosec.control.audit.ChangeListeners(
            listOf(
                object : me.ahoo.cosec.control.audit.ChangeListener {
                    override val name = "audit"
                    override val delivery = me.ahoo.cosec.runtime.event.Delivery.REQUIRED

                    override suspend fun onChange(event: me.ahoo.cosec.control.audit.ChangeEvent) = Unit
                },
            ),
        ),
    )

    @AfterEach
    fun close() {
        opened.forEach { it.cancel() }
    }

    /** A data-plane instance: its own pipeline over the current heads, its own candidate source and recorder. */
    private inner class Instance(
        name: String,
        compile: (SnapshotKey, me.ahoo.cosec.snapshot.SnapshotDocument) -> CompiledDocument = COMPILER::compile
    ) {
        val recorder = ShadowRecorder(name, clock)
        private val listeners = DecisionListeners(
            listOf(
                ShadowListener(
                    "shadow",
                    1.0,
                    CachingCandidateSource(store, compile, refreshAfter = Duration.ZERO, clock = clock),
                    recorder
                )
            ),
        ).also { opened += it }
        private val pipeline = Pipeline(
            authenticator = Authenticator {
                AuthnResult.Authenticated(
                    AuthenticatedPrincipal(PrincipalId("bob"), TenantId("acme"), NO_MEMBERSHIPS),
                    "token"
                )
            },
            snapshots = SuspendingSnapshotSource(HeadViews()),
            listeners = listeners,
        )
        val reporter = ShadowReporter(recorder, reports, { installed() }, clock)

        fun get(path: String): Int = runBlocking { pipeline.evaluate(facts(path)).status }

        /** Waits until the shadow listener has recorded [count] samples. */
        fun sampled(count: Long) = runBlocking {
            withTimeout(5.seconds) {
                while (recorder.reports().sumOf { it.counts.values.sum() } < count) {
                    delay(5)
                }
            }
        }

        private fun installed(): Map<SnapshotKey, InstalledDocument> =
            mapOf(
                StoreFixtures.ACME to InstalledDocument(
                    runBlocking {
                        revisions.head(StoreFixtures.ACME)?.revision ?: 0
                    },
                    Duration.ZERO
                )
            )
    }

    /** The current heads, compiled, with their revisions: what a confirmed source installs. */
    private inner class HeadViews : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView? = null

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(
            runBlocking {
                val tenant = key.tenant?.let { revisions.head(SnapshotKey.Tenant(it.value)) }
                SnapshotView(
                    CompiledDocument.platform(emptyList(), emptyList()),
                    null,
                    key.tenant?.let { id ->
                        tenant?.document?.let {
                            COMPILER.compile(it)
                        } ?: CompiledDocument.tenant(id, emptyList(), emptyList())
                    },
                    version = ViewVersion(0, null, tenant?.revision ?: 0),
                )
            },
        )
    }

    private suspend fun bootstrap() {
        val read = PolicyDefinition(
            PolicyId("read"),
            false,
            null,
            listOf(
                StatementSource("", Effect.ALLOW, listOf("http:GET"), listOf(ResourceSource("http:path:/orders/**")))
            ),
        )
        val everyone =
            Binding(BindingId("everyone"), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), PolicyId("read"))
        revisions.publish(
            StoreFixtures.ACME,
            null,
            StoreFixtures.candidate(StoreFixtures.ACME, listOf(read), listOf(everyone)),
            Publication("bootstrap", ProvenanceKind.BOOTSTRAP, PLATO.actor),
        )
    }

    /** A staged change that revokes everyone's read: every sampled request is NARROWED on the candidate. */
    private suspend fun staged(): ChangeRequest {
        bootstrap()
        val change = changes.propose(
            ALICE_OP,
            StoreFixtures.ACME,
            1,
            listOf(Operation.RemoveBinding(BindingId("everyone"))),
            "revoke"
        )
        return changes.stage(ALICE_OP, change.id).also { it.state.assert().isEqualTo(ChangeState.SHADOWING) }
    }

    @Test
    fun `two instances sample a window that closes from their reports, and the publisher acknowledges it`() = runBlocking<Unit> {
        val change = staged()
        val one = Instance("one")
        val two = Instance("two")
        // The candidate denies; the enforced documents still decide.
        repeat(2) { one.get("/orders/$it").assert().isEqualTo(200) }
        repeat(3) { two.get("/orders/$it").assert().isEqualTo(200) }
        one.sampled(2)
        two.sampled(3)
        one.reporter.flush()
        two.reporter.flush()
        // Enough samples, but not for long enough yet.
        changes.shadowed(change.id).state.assert().isEqualTo(ChangeState.SHADOWING)
        clock.now = clock.now.plus(Duration.ofMinutes(1))
        val closed = changes.shadowed(change.id)
        closed.state.assert().isEqualTo(ChangeState.READY)
        val summary = closed.shadow!!.closed!!
        summary.counts.getValue(ShadowOutcome.NARROWED.name).assert().isEqualTo(5L)
        summary.insufficient.assert().isFalse()
        store.candidate(StoreFixtures.ACME).assert().isNull()

        // NARROWED is not zero: the publisher acknowledges exactly these numbers.
        refused { changes.publish(ALICE_OP, change.id) }.assert().isEqualTo(428)
        refused {
            changes.publish(ALICE_OP, change.id, ShadowAck(closed.shadow!!.window, mapOf("NARROWED" to 4L)))
        }.assert().isEqualTo(428)
        refused { changes.publish(ALICE_OP, change.id, ShadowAck("another", summary.counts)) }.assert().isEqualTo(428)
        changes.publish(ALICE_OP, change.id, ShadowAck(closed.shadow!!.window, mapOf("NARROWED" to 5L))).state
            .assert().isEqualTo(ChangeState.PUBLISHED)
        val provenance = revisions.head(StoreFixtures.ACME)!!.document.provenance!!
        provenance.shadow!!.window.assert().isEqualTo(closed.shadow!!.window)

        // Propagation: both instances last reported revision 1; each reports revision 2 on its next cycle.
        changes.propagation(StoreFixtures.ACME, 2).assert().isEqualTo(Propagation(2, 0, 2))
        one.reporter.flush()
        changes.propagation(StoreFixtures.ACME, 2).assert().isEqualTo(Propagation(2, 1, 2))
        two.reporter.flush()
        changes.propagation(StoreFixtures.ACME, 2).assert().isEqualTo(Propagation(2, 2, 2))
        clock.now = clock.now.plus(Duration.ofMinutes(2))
        changes.propagation(StoreFixtures.ACME, 2).assert().isEqualTo(Propagation(2, 0, 0))
    }

    @Test
    fun `a window without enough samples closes at its longest as insufficient`() = runBlocking<Unit> {
        val change = staged()
        val one = Instance("one")
        one.get("/orders/1")
        one.sampled(1)
        one.reporter.flush()
        clock.now = clock.now.plus(Duration.ofMinutes(5))
        changes.shadowed(change.id).state.assert().isEqualTo(ChangeState.SHADOWING)
        clock.now = clock.now.plus(Duration.ofMinutes(5))
        val closed = changes.shadowed(change.id)
        closed.shadow!!.closed!!.insufficient.assert().isTrue()
        refused { changes.publish(ALICE_OP, change.id) }.assert().isEqualTo(428)
        changes.publish(ALICE_OP, change.id, ShadowAck(closed.shadow!!.window, closed.shadow!!.closed!!.counts)).state
            .assert().isEqualTo(ChangeState.PUBLISHED)
    }

    @Test
    fun `a candidate an instance cannot compile closes the window at once and never publishes`() = runBlocking<Unit> {
        val change = staged()
        val broken = Instance("broken") { _, _ -> error("this instance lacks an attribute provider") }
        broken.get("/orders/1").assert().isEqualTo(200)
        broken.sampled(1)
        broken.reporter.flush()
        val closed = changes.shadowed(change.id)
        closed.state.assert().isEqualTo(ChangeState.READY)
        closed.shadow!!.closed!!.blocked.assert().isTrue()
        refused {
            changes.publish(ALICE_OP, change.id, ShadowAck(closed.shadow!!.window, closed.shadow!!.closed!!.counts))
        }.assert().isEqualTo(409)
        revisions.head(StoreFixtures.ACME)!!.revision.assert().isEqualTo(1)
    }

    @Test
    fun `a head that moves during the window conflicts, and abandoning withdraws the candidate`() = runBlocking<Unit> {
        val change = staged()
        store.candidate(StoreFixtures.ACME)!!.window.assert().isEqualTo(change.shadow!!.window)
        val head = revisions.head(StoreFixtures.ACME)
        revisions.publish(
            StoreFixtures.ACME,
            head,
            head!!.document,
            Publication("other", ProvenanceKind.PUBLISH, PLATO.actor)
        )
        changes.shadowed(change.id).state.assert().isEqualTo(ChangeState.CONFLICTED)
        store.candidate(StoreFixtures.ACME).assert().isNull()

        val again = changes.rebase(ALICE_OP, change.id)
        changes.stage(ALICE_OP, again.id)
        store.candidate(StoreFixtures.ACME).assert().isNotNull()
        changes.abandon(ALICE_OP, again.id)
        store.candidate(StoreFixtures.ACME).assert().isNull()
    }

    @Test
    fun `an instance behind the candidate's base compares nothing`() = runBlocking<Unit> {
        val change = staged()
        val candidate = store.candidate(StoreFixtures.ACME)!!
        store.putCandidate(
            StoreFixtures.ACME,
            me.ahoo.cosec.runtime.shadow.StoredCandidate(candidate.window, 7, candidate.document)
        )
        val one = Instance("one")
        one.get("/orders/1")
        one.sampled(1)
        one.recorder.reports().single().counts.assert().isEqualTo(mapOf(ShadowOutcome.BASE_MISMATCH to 1L))
        one.reporter.flush()
        clock.now = clock.now.plus(Duration.ofMinutes(10))
        // A base mismatch is no evidence: the window closes for lack of samples.
        changes.shadowed(change.id).shadow!!.closed!!.insufficient.assert().isTrue()
    }

    @Test
    fun `break-glass has no window, and skipping one needs a reason that is recorded`() = runBlocking<Unit> {
        bootstrap()
        val skipped = changes.propose(
            ALICE_OP,
            StoreFixtures.ACME,
            1,
            listOf(Operation.RemoveBinding(BindingId("everyone"))),
            "urgent"
        )
        changes.skipShadow(ALICE_OP, skipped.id, "an incident").state.assert().isEqualTo(ChangeState.READY)
        store.candidate(StoreFixtures.ACME).assert().isNull()
        changes.publish(ALICE_OP, skipped.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        revisions.head(StoreFixtures.ACME)!!.document.provenance!!.shadow!!.skipped.assert().isTrue()

        val glass = Binding(
            BindingId("glass"),
            SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("plato")),
            BindingScope(Match.Any, Match.Any),
            PolicyId("cosec:root"),
            expiresAt = clock.now.plusSeconds(3600).toEpochMilli(),
            breakGlass = true,
        )
        val request = changes.propose(
            PLATO,
            SnapshotKey.Platform,
            0,
            listOf(Operation.PutBinding(glass)),
            "incident",
            ProvenanceKind.BREAK_GLASS
        )
        changes.stage(PLATO, request.id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        store.candidate(SnapshotKey.Platform).assert().isNull()
    }

    private suspend fun refused(block: suspend () -> Unit): Int = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e.status
    }

    private companion object {
        val COMPILER = PolicyDocumentCompiler()

        fun facts(path: String) = HttpFacts(
            method = "GET",
            rawPath = path,
            rawQuery = null,
            headers = Headers.of(emptyMap()),
            cookies = Cookies.of(emptyMap()),
            remoteAddress = "10.0.0.1",
            host = HostScope(null),
        )
    }
}
