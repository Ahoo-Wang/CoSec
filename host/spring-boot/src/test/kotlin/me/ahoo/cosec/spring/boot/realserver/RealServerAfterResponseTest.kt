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

package me.ahoo.cosec.spring.boot.realserver

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.audit.AuditEvents
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.RequestGate
import me.ahoo.cosec.webflux.ReactiveXForwardedRemoteIpResolver
import me.ahoo.cosec.webmvc.ServletXForwardedRemoteIpResolver
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `PIP-32`: nothing reads the server's request after the response. The readers hand the pipeline views over the
 * server's headers and cookies; whatever outlives the request carries copies made while it was decided. Each request
 * here is permitted, with a header the pipeline reads (`CoSec-Device-Id`) and a forwarded address, and each consumer
 * that outlives it reads it only once the client holds the response, which on Tomcat is when the server's request is
 * recycled and refuses to be read: a BEST_EFFORT decision listener, the audit record made from its event, and the
 * security context an application keeps. The shadow replay copies the headers it replays
 * (`BreakGlassAndShadowTest`), and a check copies them before the request ends (`CheckAfterResponseTest`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealServerAfterResponseTest {
    /** A BEST_EFFORT listener that reads the event only once the test lets it, after the response. */
    private class LateListener : DecisionListener {
        override val name: String = "late"

        @Volatile
        var respond = CountDownLatch(1)

        @Volatile
        var read = CompletableFuture<Map<String, Any?>>()

        override suspend fun onDecision(event: DecisionEvent) {
            respond.await(BOUND_SECONDS, TimeUnit.SECONDS)
            read.complete(described(event))
        }
    }

    /** The pipeline, keeping the facts it read and the outcome it answered for the last request. */
    private class Kept(private val pipeline: RequestGate) : RequestGate {
        @Volatile
        var facts: HttpFacts? = null

        @Volatile
        var outcome: Outcome? = null

        override suspend fun evaluate(facts: HttpFacts): Outcome = pipeline.evaluate(facts).also {
            this.facts = facts
            outcome = it
        }
    }

    private val listener = LateListener()
    private val fixture = ParityFixture(listeners = DecisionListeners(listOf(listener)))
    private val kept = STACKS.associateWith { Kept(fixture.pipeline) }
    private val servers = STACKS.associateWith {
        RealServer(
            it,
            kept.getValue(it),
            RealServerRemoteIp(ServletXForwardedRemoteIpResolver(1), ReactiveXForwardedRemoteIpResolver(1)),
        )
    }

    @AfterAll
    fun close() {
        servers.values.forEach { it.close() }
        fixture.close()
    }

    fun stacks(): List<Stack> = STACKS

    /** Sends the permitted request on [stack], and waits, on Tomcat, until its request is recycled. */
    private fun respond(stack: Stack): Kept {
        listener.respond = CountDownLatch(1)
        listener.read = CompletableFuture()
        val response = servers.getValue(stack).send(REQUEST)
        response.status.assert().describedAs("$stack: ${response.body}").isEqualTo(200)
        val request = kept.getValue(stack)
        if (stack == Stack.TOMCAT) {
            // The proof that a view could not be read now: Tomcat refuses its recycled request.
            val facts = checkNotNull(request.facts)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS)
            var refused: Throwable? = null
            while (refused == null && System.nanoTime() < deadline) {
                refused = runCatching { facts.headers.values(DEVICE_HEADER) }.exceptionOrNull()
            }
            val after = "Tomcat's request after the response"
            refused.assert().describedAs(after).isInstanceOf(IllegalStateException::class.java)
        }
        return request
    }

    @ParameterizedTest
    @MethodSource("stacks")
    fun `a listener and the audit record read after the response what the request carried`(stack: Stack) {
        respond(stack)
        listener.respond.countDown()
        listener.read.get(BOUND_SECONDS, TimeUnit.SECONDS).assert().describedAs("$stack").isEqualTo(EXPECTED)
    }

    @ParameterizedTest
    @MethodSource("stacks")
    fun `a security context kept after the response answers what the request carried, and admits no check`(
        stack: Stack
    ) {
        val request = respond(stack)
        listener.respond.countDown()
        val context = checkNotNull(request.outcome?.security).securityContext
        context.principal.id.assert().isEqualTo("alice")
        context.tenantId.assert().isEqualTo("acme")
        context.deviceId.assert().isEqualTo(DEVICE)
        context.requestId.assert().isEqualTo(ParityFixture.REQUEST_ID)
        runBlocking { AuthorizationChecker(fixture.pipeline).check(context, "http:GET", "http:path:/orders/1") }
            .assert().describedAs("$stack: a check after the response").isFalse()
    }

    private companion object {
        val STACKS = Stack.entries - Stack.GATEWAY
        const val BOUND_SECONDS = 5L
        const val DEVICE_HEADER = "CoSec-Device-Id"
        const val DEVICE = "device-7"
        const val CLIENT = "203.0.113.7"
        val REQUEST = "GET /orders/1 HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer alice@acme:clerk\r\n" +
            "$DEVICE_HEADER: $DEVICE\r\nX-Forwarded-For: $CLIENT\r\nConnection: close\r\n\r\n"

        val EXPECTED: Map<String, Any?> = mapOf(
            "method" to "GET",
            "path" to "/orders/1",
            "remoteIp" to CLIENT,
            "deviceId" to DEVICE,
            "subject" to "alice@acme",
            "status" to 200,
            "audit" to listOf("GET", "/orders/1", CLIENT, DEVICE, "alice", "acme"),
        )

        fun described(event: DecisionEvent): Map<String, Any?> {
            val audit = AuditEvents.from(event)
            return mapOf(
                "method" to event.request.method,
                "path" to event.request.path,
                "remoteIp" to event.request.remoteIp,
                "deviceId" to event.request.deviceId,
                "subject" to "${event.subject.id}@${event.subject.tenant}",
                "status" to event.outcome.status,
                "audit" to listOf(
                    audit.request.method,
                    audit.request.path,
                    audit.request.remoteIp,
                    audit.request.device.id,
                    audit.principal.id,
                    audit.tenantId,
                ),
            )
        }
    }
}
