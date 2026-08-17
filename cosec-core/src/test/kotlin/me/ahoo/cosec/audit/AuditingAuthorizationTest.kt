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

package me.ahoo.cosec.audit

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.audit.AuditDecision
import me.ahoo.cosec.api.audit.AuditEvent
import me.ahoo.cosec.api.audit.AuditEventSink
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.time.Duration

class AuditingAuthorizationTest {
    private val attributeMap = mutableMapOf<String, Any>()
    private val request = mockk<Request> {
        every { appId } returns "app"
        every { spaceId } returns "0"
        every { deviceId } returns ""
        every { requestId } returns "req-1"
        every { remoteIp } returns "1.2.3.4"
        every { method } returns "POST"
        every { path } returns "/api/orders"
    }
    private val context = mockk<SecurityContext> {
        every { tenant.tenantId } returns "t1"
        every { principal.id } returns "u1"
        every { principal.authenticated } returns true
        every { principal.roles } returns emptySet()
        every { principal.policies } returns emptySet()
        every { attributes } returns attributeMap
        every { setAttributeValue(any(), any()) } answers {
            attributeMap[firstArg()] = secondArg()
            self as SecurityContext
        }
        every { getAttributeValue<Any>(any()) } answers { attributeMap[firstArg()] }
    }

    private fun authorization(result: Mono<AuthorizeResult>): Pair<Authorization, MutableList<AuditEvent>> {
        val events = mutableListOf<AuditEvent>()
        val sink = AuditEventSink(events::add)
        val delegate = mockk<Authorization> {
            every { authorize(request, context) } returns result
        }
        return AuditingAuthorization(delegate, sink) to events
    }

    @Test
    fun publishWhenAllow() {
        val (authorization, events) = authorization(AuthorizeResult.ALLOW.toMono())
        authorization.authorize(request, context)
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
        events.size.assert().isEqualTo(1)
        events[0].decision.assert().isEqualTo(AuditDecision.ALLOW)
    }

    @Test
    fun publishWhenDeny() {
        val (authorization, events) = authorization(AuthorizeResult.EXPLICIT_DENY.toMono())
        authorization.authorize(request, context)
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
            .verifyComplete()
        events[0].decision.assert().isEqualTo(AuditDecision.EXPLICIT_DENY)
    }

    @Test
    fun publishWhenTooManyRequestsAndErrorPropagates() {
        val (authorization, events) = authorization(TooManyRequestsException().toMono<AuthorizeResult>())
        authorization.authorize(request, context)
            .test()
            .expectError(TooManyRequestsException::class.java)
            .verify()
        events.size.assert().isEqualTo(1)
        events[0].decision.assert().isEqualTo(AuditDecision.TOO_MANY_REQUESTS)
    }

    @Test
    fun publishWhenUnexpectedErrorAndErrorPropagates() {
        val (authorization, events) = authorization(IllegalStateException().toMono<AuthorizeResult>())
        authorization.authorize(request, context)
            .test()
            .expectError(IllegalStateException::class.java)
            .verify()
        events[0].decision.assert().isEqualTo(AuditDecision.ERROR)
    }

    @Test
    fun sinkFailureDoesNotAffectAuthorization() {
        val delegate = mockk<Authorization> {
            every { authorize(request, context) } returns AuthorizeResult.ALLOW.toMono()
        }
        val sink = mockk<AuditEventSink> {
            every { publish(any()) } throws IllegalStateException("sink down")
        }
        val authorization = AuditingAuthorization(delegate, sink)
        authorization.authorize(request, context)
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
    }

    @Test
    fun extractorFailureDoesNotAffectAuthorization() {
        val throwingRequest = mockk<Request> {
            every { appId } returns "app"
            every { spaceId } returns "0"
            every { deviceId } returns ""
            every { requestId } returns "req-1"
            every { remoteIp } returns "1.2.3.4"
            every { method } returns "POST"
            every { path } throws IllegalStateException("boom")
        }
        val events = mutableListOf<AuditEvent>()
        val sink = AuditEventSink(events::add)
        val delegate = mockk<Authorization> {
            every { authorize(throwingRequest, context) } returns AuthorizeResult.ALLOW.toMono()
        }
        val authorization = AuditingAuthorization(delegate, sink)
        authorization.authorize(throwingRequest, context)
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
        events.size.assert().isEqualTo(0)
    }

    @Test
    fun elapsedNanosMeasuredFromSubscribeToSignal() {
        val (authorization, events) = authorization(
            Mono.delay(Duration.ofMillis(50)).map { AuthorizeResult.ALLOW },
        )
        authorization.authorize(request, context)
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
        events[0].elapsedNanos.assert().isGreaterThanOrEqualTo(50_000_000)
    }

    @Test
    fun delegateIsWrapped() {
        val delegate = mockk<Authorization>()
        val authorization = AuditingAuthorization(delegate, AuditEventSink { })
        authorization.delegate.assert().isSameAs(delegate)
    }
}
