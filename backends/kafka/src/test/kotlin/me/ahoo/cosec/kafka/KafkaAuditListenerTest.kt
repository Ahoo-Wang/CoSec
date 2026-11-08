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

package me.ahoo.cosec.kafka

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.audit.AuditDecision
import me.ahoo.cosec.api.audit.AuditReasonCode
import me.ahoo.cosec.api.audit.AuditSourceType
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.audit.AuditAuthorizationData
import me.ahoo.cosec.runtime.audit.AuditDeviceData
import me.ahoo.cosec.runtime.audit.AuditEventData
import me.ahoo.cosec.runtime.audit.AuditEvents
import me.ahoo.cosec.runtime.audit.AuditPolicyData
import me.ahoo.cosec.runtime.audit.AuditPrincipalData
import me.ahoo.cosec.runtime.audit.AuditRequestData
import me.ahoo.cosec.runtime.audit.AuditSourceData
import me.ahoo.cosec.runtime.audit.AuditStatementData
import me.ahoo.cosec.runtime.audit.AuditTraceData
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.support.SendResult
import java.time.Instant
import java.util.concurrent.CompletableFuture

/** RFC 0006 §9.4.1, §9.5.3: the Kafka audit as a listener; REQUIRED waits for the broker, and its failure is a 503. */
class KafkaAuditListenerTest {
    private val opened = ArrayList<AutoCloseable>()

    @AfterEach
    fun close() {
        opened.forEach { it.close() }
        unmockkObject(AuditEvents)
    }

    private object Views : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            null,
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    private val permit = Decision.Permit(StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Attached(PolicyId("p"))))

    private fun evaluate(
        listener: KafkaAuditListener,
        decision: Decision = permit,
        requiredTimeout: kotlin.time.Duration = kotlin.time.Duration.parse("1s"),
    ): Outcome {
        val listeners = DecisionListeners(listOf(listener), requiredTimeout = requiredTimeout)
        opened += listeners
        opened += AutoCloseable { listener.destroy() }
        val engine = object : FlatEngine {
            override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel) =
                FlatEvaluation(decision, null)
        }
        val claims = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())
        val principal = AuthenticatedPrincipal(PrincipalId("alice"), TenantId("acme"), claims)
        val pipeline = Pipeline(
            PipelineComponents(
                { AuthnResult.Authenticated(principal, "token") },
                SuspendingSnapshotSource(Views),
                engine = engine,
                listeners = listeners,
            )
        )
        val facts = HttpFacts(
            "GET",
            "/orders",
            null,
            Headers.of(emptyMap()),
            Cookies.EMPTY,
            "10.0.0.1",
            HostScope(null)
        )
        return runBlocking { pipeline.evaluate(facts) }
    }

    @Test
    fun aRequiredAuditWaitsForTheBrokerAndWritesTheSameKeyAndValue() {
        val key = slot<String>()
        val value = slot<String>()
        val kafka = mockk<KafkaOperations<String, String>> {
            every { send("audit", capture(key), capture(value)) } returns
                CompletableFuture.completedFuture(mockk<SendResult<String, String>>())
        }
        evaluate(KafkaAuditListener(kafka, "audit", Delivery.REQUIRED)).status.assert().isEqualTo(200)
        key.captured.assert().isEqualTo("""["acme","alice"]""")
        CoSecJson.mapper.readTree(value.captured).get("principal").get("id").asString().assert().isEqualTo("alice")
    }

    @Test
    fun aRequiredAuditTheBrokerRejectsTurnsAPermitInto503() {
        val kafka = mockk<KafkaOperations<String, String>> {
            every { send(any<String>(), any<String>(), any<String>()) } returns
                CompletableFuture.failedFuture(IllegalStateException("broker down"))
        }
        evaluate(KafkaAuditListener(kafka, "audit", Delivery.REQUIRED)).status.assert().isEqualTo(503)
        evaluate(KafkaAuditListener(kafka, "audit", Delivery.REQUIRED), Decision.Deny(null)).status.assert()
            .isEqualTo(403)
    }

    @Test
    fun aBestEffortAuditNeverChangesTheOutcome() {
        val sent = CompletableFuture<Unit>()
        val kafka = mockk<KafkaOperations<String, String>> {
            every { send(any<String>(), any<String>(), any<String>()) } answers {
                sent.complete(Unit)
                CompletableFuture.failedFuture(IllegalStateException("broker down"))
            }
        }
        evaluate(KafkaAuditListener(kafka, "audit")).status.assert().isEqualTo(200)
        sent.get(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** Review: a broker that hangs never piles REQUIRED sends up without bound; a full queue fails at once. */
    @Test
    fun aFullSendQueueFailsTheRequiredDeliveryAtOnce() {
        val release = java.util.concurrent.CountDownLatch(1)
        val sends = java.util.concurrent.atomic.AtomicInteger()
        val kafka = mockk<KafkaOperations<String, String>> {
            every { send(any<String>(), any<String>(), any<String>()) } answers {
                sends.incrementAndGet()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                CompletableFuture.completedFuture(mockk<SendResult<String, String>>())
            }
        }
        val listener = KafkaAuditListener(kafka, "audit", Delivery.REQUIRED, queueCapacity = 1)
        val timeout = kotlin.time.Duration.parse("500ms")
        // The first send blocks the sender thread; the second waits in the queue of one.
        evaluate(listener, requiredTimeout = timeout).status.assert().isEqualTo(503)
        evaluate(listener, requiredTimeout = timeout).status.assert().isEqualTo(503)
        val started = System.nanoTime()
        evaluate(listener, requiredTimeout = timeout).status.assert().isEqualTo(503)
        // The third did not wait for its timeout: the queue was full.
        (System.nanoTime() - started).assert().isLessThan(timeout.inWholeNanoseconds / 2)
        release.countDown()
        // The cancelled, queued send is dropped, not made.
        Thread.sleep(300)
        sends.get().assert().isEqualTo(1)
    }

    @Test
    fun aBlankTopicIsRefused() {
        assertThrows<IllegalArgumentException> { KafkaAuditListener(mockk(), " ") }
    }

    private val audit = AuditEventData(
        eventId = "event-1",
        timestamp = Instant.parse("2026-08-18T00:00:00Z"),
        tenantId = "tenant-1",
        principal = AuditPrincipalData(
            id = "principal-1",
            authenticated = true,
            roles = setOf("admin"),
            policies = setOf("orders"),
        ),
        request = AuditRequestData(
            id = "request-1",
            appId = "app-1",
            spaceId = "space-1",
            remoteIp = "127.0.0.1",
            method = "GET",
            path = "/orders/1",
            routeId = "orders",
            device = AuditDeviceData(id = "device-1", userAgent = "test-agent"),
        ),
        authorization = AuditAuthorizationData(
            decision = AuditDecision.ALLOW,
            reasonCode = AuditReasonCode.ALLOW,
            reason = "Allow",
            elapsedNanos = 100,
            source = AuditSourceData(
                type = AuditSourceType.GLOBAL_POLICY,
                policy = AuditPolicyData(
                    id = "orders",
                    type = PolicyType.GLOBAL,
                    statement = AuditStatementData(index = 0, name = "read"),
                ),
            ),
        ),
        trace = AuditTraceData(traceId = "trace-1", spanId = "span-1"),
    )

    /** The key and value the listener sends for [event], whatever the pipeline's own event would map to. */
    private fun recordOf(event: AuditEventData): Pair<String, String> {
        mockkObject(AuditEvents)
        every { AuditEvents.from(any()) } returns event
        val key = slot<String>()
        val value = slot<String>()
        val kafka = mockk<KafkaOperations<String, String>> {
            every { send("audit", capture(key), capture(value)) } returns
                CompletableFuture.completedFuture(mockk<SendResult<String, String>>())
        }
        evaluate(KafkaAuditListener(kafka, "audit", Delivery.REQUIRED)).status.assert().isEqualTo(200)
        return key.captured to value.captured
    }

    /**
     * The record value consumers read, pinned: a mapper or Jackson change that alters it fails here (RFC 0011 D3). The
     * fixture is what `main`'s `CoSecJsonSerializer` wrote for this event before RFC 0011 PR-2 (`origin/main` at
     * da58e780).
     */
    @Test
    fun theRecordValueIsPinned() {
        val pinned = checkNotNull(javaClass.getResource("/stored-format/audit.json")).readText()
        recordOf(audit).second.assert().isEqualTo(pinned)
    }

    @Test
    fun theRecordKeepsEachFieldUnderItsOwner() {
        val json = CoSecJson.mapper.readTree(recordOf(audit).second)
        json.has("principalId").assert().isFalse()
        json.has("requestId").assert().isFalse()
        json.has("decision").assert().isFalse()
        json.get("principal").get("id").asString().assert().isEqualTo("principal-1")
        json.get("request").get("id").asString().assert().isEqualTo("request-1")
        json.get("authorization").get("source").get("policy").get("id").asString().assert().isEqualTo("orders")
        json.get("trace").get("traceId").asString().assert().isEqualTo("trace-1")
    }

    /** A `:` in an id cannot make two principals share a key: the key is a JSON array. */
    @Test
    fun recordKeysAreUnambiguous() {
        val first = recordOf(audit.copy(tenantId = "a", principal = audit.principal.copy(id = "b:c"))).first
        val second = recordOf(audit.copy(tenantId = "a:b", principal = audit.principal.copy(id = "c"))).first
        first.assert().isEqualTo("""["a","b:c"]""")
        second.assert().isEqualTo("""["a:b","c"]""")
    }
}
