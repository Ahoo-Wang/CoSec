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
import me.ahoo.cosec.api.audit.AuditDocument
import me.ahoo.cosec.api.audit.AuditReasonCode
import me.ahoo.cosec.api.audit.AuditVia
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
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.support.SendResult
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

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
        return evaluate(listeners, decision)
    }

    private fun evaluate(listeners: DecisionListeners, decision: Decision = permit): Outcome {
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

    /**
     * #1250, the real lifecycle: Spring drains the listeners, destroys this listener and only then the producer, so a
     * send still queued here must go out first. Each send takes 200 ms; the budget is far longer.
     */
    @Test
    fun aQueuedBestEffortRecordIsSentBeforeTheProducerIsDestroyed() {
        val destroyed = AtomicBoolean()
        val sends = AtomicInteger()
        val sendsAfterDestroy = AtomicInteger()
        val kafka = mockk<KafkaOperations<String, String>>(moreInterfaces = arrayOf(DisposableBean::class)) {
            every { send(any<String>(), any<String>(), any<String>()) } answers {
                if (destroyed.get()) sendsAfterDestroy.incrementAndGet()
                sends.incrementAndGet()
                Thread.sleep(200)
                CompletableFuture.completedFuture(mockk<SendResult<String, String>>())
            }
            every { (this@mockk as DisposableBean).destroy() } answers { destroyed.set(true) }
        }
        val context = AnnotationConfigApplicationContext()
        context.registerBean("producer", KafkaOperations::class.java, Supplier { kafka })
        context.registerBean("audit", KafkaAuditListener::class.java, Supplier { KafkaAuditListener(kafka, "audit") })
        context.registerBean(
            "listeners",
            DecisionListeners::class.java,
            Supplier { DecisionListeners(listOf(context.getBean(KafkaAuditListener::class.java))) },
            BeanDefinitionCustomizer { it.destroyMethodName = "drain" },
        )
        context.refresh()
        val listeners = context.getBean(DecisionListeners::class.java)
        evaluate(listeners)
        evaluate(listeners)

        context.close()

        destroyed.get().assert().isTrue()
        sends.get().assert().isEqualTo(2)
        sendsAfterDestroy.get().assert().isEqualTo(0)
    }

    /** #1250: a send handed to the producer is not done until the broker answers. */
    @Test
    fun shutdownWaitsForTheBrokerToAnswerASend() {
        val answer = CompletableFuture<SendResult<String, String>>()
        val kafka = mockk<KafkaOperations<String, String>> {
            every { send(any<String>(), any<String>(), any<String>()) } returns answer
        }
        val listener = KafkaAuditListener(kafka, "audit")
        val listeners = DecisionListeners(listOf(listener))
        opened += listeners
        evaluate(listeners)
        listeners.drain().assert().isTrue()
        CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute {
            answer.complete(mockk<SendResult<String, String>>())
        }

        listener.destroy()

        answer.isDone.assert().isTrue()
    }

    /**
     * #1250: a producer that never answers cannot hang shutdown. At the bound a send still queued is dropped, not made,
     * and the one in progress is interrupted; a client that ignores the interrupt keeps its one send and gets no other.
     */
    @Test
    fun shutdownEndsAtItsBoundAndMakesNoQueuedSendAfterIt() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val sends = AtomicInteger()
        val kafka = mockk<KafkaOperations<String, String>> {
            every { send(any<String>(), any<String>(), any<String>()) } answers {
                sends.incrementAndGet()
                started.countDown()
                while (true) {
                    try {
                        release.await()
                        break
                    } catch (_: InterruptedException) {
                        interrupted.countDown()
                    }
                }
                CompletableFuture.completedFuture(mockk<SendResult<String, String>>())
            }
        }
        val bound = 300.milliseconds
        val listener = KafkaAuditListener(kafka, "audit", drainTimeout = bound)
        val listeners = DecisionListeners(listOf(listener))
        opened += listeners
        evaluate(listeners)
        evaluate(listeners)
        // Both records are now the Kafka listener's: one send in progress, one queued behind it.
        listeners.drain().assert().isTrue()
        started.await(5, TimeUnit.SECONDS).assert().isTrue()

        val startedAt = System.nanoTime()
        listener.destroy()
        val elapsed = (System.nanoTime() - startedAt).nanoseconds

        elapsed.assert().isGreaterThanOrEqualTo(bound)
        elapsed.assert().isLessThan(5.seconds)
        // On the sender's own thread, so it may land just after destroy() returns.
        interrupted.await(5, TimeUnit.SECONDS).assert().isTrue()
        release.countDown()
        Thread.sleep(200)
        sends.get().assert().isEqualTo(1)
    }

    /** #1250: a broker that never answers a send cannot hang shutdown either. */
    @Test
    fun shutdownEndsAtItsBoundWhenTheBrokerNeverAnswers() {
        val kafka = mockk<KafkaOperations<String, String>> {
            every { send(any<String>(), any<String>(), any<String>()) } returns CompletableFuture()
        }
        val bound = 300.milliseconds
        val listener = KafkaAuditListener(kafka, "audit", drainTimeout = bound)
        val listeners = DecisionListeners(listOf(listener))
        opened += listeners
        evaluate(listeners)
        listeners.drain().assert().isTrue()

        val startedAt = System.nanoTime()
        listener.destroy()
        val elapsed = (System.nanoTime() - startedAt).nanoseconds

        elapsed.assert().isGreaterThanOrEqualTo(bound)
        elapsed.assert().isLessThan(5.seconds)
    }

    /** A send that fails during the wait was answered: shutdown goes on, it does not sit out its bound. */
    @Test
    fun aSendThatFailsDuringShutdownIsAnsweredAndDoesNotHoldIt() {
        val answer = CompletableFuture<SendResult<String, String>>()
        val kafka = mockk<KafkaOperations<String, String>> {
            every { send(any<String>(), any<String>(), any<String>()) } returns answer
        }
        val listener = KafkaAuditListener(kafka, "audit", drainTimeout = 5.seconds)
        val listeners = DecisionListeners(listOf(listener))
        opened += listeners
        evaluate(listeners)
        listeners.drain().assert().isTrue()
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute {
            answer.completeExceptionally(IllegalStateException("broker down"))
        }

        val startedAt = System.nanoTime()
        listener.destroy()

        answer.isCompletedExceptionally.assert().isTrue()
        (System.nanoTime() - startedAt).nanoseconds.assert().isLessThan(2.seconds)
    }

    /** An interrupted shutdown stops waiting at once and leaves the interrupt for whoever asked. */
    @Test
    fun anInterruptedShutdownStopsWaitingAndKeepsTheInterrupt() {
        val kafka = mockk<KafkaOperations<String, String>> {
            every { send(any<String>(), any<String>(), any<String>()) } returns CompletableFuture()
        }
        val listener = KafkaAuditListener(kafka, "audit", drainTimeout = 5.seconds)
        val listeners = DecisionListeners(listOf(listener))
        opened += listeners
        evaluate(listeners)
        listeners.drain().assert().isTrue()

        val startedAt = System.nanoTime()
        Thread.currentThread().interrupt()
        listener.destroy()

        // Reading the flag clears it, so the interrupt does not reach the next test.
        Thread.interrupted().assert().isTrue()
        (System.nanoTime() - startedAt).nanoseconds.assert().isLessThan(2.seconds)
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
        ),
        request = AuditRequestData(
            id = "request-1",
            appId = "app-1",
            spaceId = "space-1",
            remoteIp = "127.0.0.1",
            method = "GET",
            path = "/orders/1",
            routeId = "orders",
            device = AuditDeviceData(id = "device-1"),
        ),
        authorization = AuditAuthorizationData(
            decision = AuditDecision.ALLOW,
            reasonCode = AuditReasonCode.ALLOW,
            reason = "Allow",
            elapsedNanos = 100,
            source = AuditSourceData(
                via = AuditVia.BINDING,
                document = AuditDocument.TENANT,
                policy = "orders",
                statement = AuditStatementData(index = 0, name = "read"),
            ),
        ),
        trace = AuditTraceData(traceId = "trace-1", spanId = "span-1"),
        binding = "role:reader@app-1:order.read",
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
     * The record value consumers read, pinned: a mapper or Jackson change that alters it fails here (RFC 0011 D3).
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
        json.get("authorization").get("source").get("policy").asString().assert().isEqualTo("orders")
        json.get("authorization").get("source").get("statement").get("name").asString().assert().isEqualTo("read")
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
