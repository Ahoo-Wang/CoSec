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
import me.ahoo.cosec.api.audit.AuditSourceType
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.PolicyVerifyContext
import me.ahoo.cosec.authorization.PolicyVerifySource
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.authorization.VerifyContext.Companion.setVerifyContext
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.token.TokenExpiredException
import me.ahoo.cosec.token.TokenVerificationContexts.setTokenVerificationException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        every { getHeader("User-Agent") } returns "test-agent"
        every { attributes } returns emptyMap()
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
        events[0].authorization.decision.assert().isEqualTo(AuditDecision.ALLOW)
    }

    @Test
    fun publishWhenDeny() {
        val (authorization, events) = authorization(AuthorizeResult.EXPLICIT_DENY.toMono())
        authorization.authorize(request, context)
            .test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY)
            .verifyComplete()
        events[0].authorization.decision.assert().isEqualTo(AuditDecision.EXPLICIT_DENY)
    }

    @Test
    fun publishRootSource() {
        val events = mutableListOf<AuditEvent>()
        val rootContext = SimpleSecurityContext(SimplePrincipal(CoSecPrincipal.ROOT_ID))
        val authorization = AuditingAuthorization(
            SimpleAuthorization(mockk(), mockk()),
            AuditEventSink(events::add),
        )

        authorization.authorize(request, rootContext).block()

        events.single().authorization.source.type.assert().isEqualTo(AuditSourceType.ROOT)
    }

    @Test
    fun publishBlacklistSource() {
        val events = mutableListOf<AuditEvent>()
        val blacklistChecker = mockk<BlacklistChecker> {
            every { check(request, context) } returns Mono.just(false)
        }
        val authorization = AuditingAuthorization(
            SimpleAuthorization(mockk(), mockk(), blacklistChecker),
            AuditEventSink(events::add),
        )

        authorization.authorize(request, context).block()

        events.single().authorization.source.type.assert().isEqualTo(AuditSourceType.BLACKLIST)
    }

    @Test
    fun publishNoMatchSource() {
        val events = mutableListOf<AuditEvent>()
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.just(emptyList())
        }
        val authorization = AuditingAuthorization(
            SimpleAuthorization(policyRepository, mockk()),
            AuditEventSink(events::add),
        )

        authorization.authorize(request, context).block()

        events.single().authorization.source.type.assert().isEqualTo(AuditSourceType.NONE)
    }

    @Test
    fun publishImplicitDenyWhenDelegateCompletesEmpty() {
        val (authorization, events) = authorization(Mono.empty())
        authorization.authorize(request, context)
            .test()
            .verifyComplete()
        events.single().authorization.decision.assert().isEqualTo(AuditDecision.IMPLICIT_DENY)
    }

    @Test
    fun clearStaleVerifyContextBeforeAuthorization() {
        context.setVerifyContext(
            PolicyVerifyContext(
                source = PolicyVerifySource.GLOBAL,
                policy = mockk<Policy> {
                    every { id } returns "stale-policy"
                    every { type } returns PolicyType.GLOBAL
                },
                statementIndex = 0,
                statement = mockk<Statement> { every { name } returns "stale-statement" },
                result = VerifyResult.EXPLICIT_DENY,
            )
        )
        val (authorization, events) = authorization(AuthorizeResult.IMPLICIT_DENY.toMono())
        authorization.authorize(request, context).block()
        events.single().apply {
            this.authorization.decision.assert().isEqualTo(AuditDecision.IMPLICIT_DENY)
            this.authorization.source.policy.assert().isNull()
        }
    }

    @Test
    fun isolateVerifyContextPerConcurrentAuthorization() {
        val firstPolicy = mockk<Policy> {
            every { id } returns "first-policy"
            every { type } returns PolicyType.GLOBAL
            every { condition } returns AllConditionMatcher.INSTANCE
            every { statements } returns listOf(
                StatementData(name = "first-statement", effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE),
            )
        }
        val secondPolicy = mockk<Policy> {
            every { id } returns "second-policy"
            every { type } returns PolicyType.GLOBAL
            every { condition } returns AllConditionMatcher.INSTANCE
            every { statements } returns listOf(
                StatementData(name = "second-statement", effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE),
            )
        }
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returnsMany listOf(listOf(firstPolicy).toMono(), listOf(secondPolicy).toMono())
        }
        val delegate = SimpleAuthorization(policyRepository, mockk<AppRolePermissionRepository>())
        val events = CopyOnWriteArrayList<AuditEvent>()
        val authorization = AuditingAuthorization(delegate, AuditEventSink(events::add))
        val firstStored = CountDownLatch(1)
        val secondStored = CountDownLatch(1)
        val backingAttributes = ConcurrentHashMap<String, Any>()
        val attributes = object : MutableMap<String, Any> by backingAttributes {
            override fun put(key: String, value: Any): Any? {
                val previous = backingAttributes.put(key, value)
                when ((value as? PolicyVerifyContext)?.policy?.id) {
                    "first-policy" -> {
                        firstStored.countDown()
                        secondStored.await(5, TimeUnit.SECONDS).assert().isTrue()
                    }

                    "second-policy" -> secondStored.countDown()
                }
                return previous
            }
        }
        val sharedContext = SimpleSecurityContext(SimpleTenantPrincipal.ANONYMOUS, attributes = attributes)
        val firstRequest = requestForPath("/first")
        val secondRequest = requestForPath("/second")

        val first = authorization.authorize(firstRequest, sharedContext)
            .subscribeOn(Schedulers.boundedElastic())
            .toFuture()
        firstStored.await(5, TimeUnit.SECONDS).assert().isTrue()
        val second = authorization.authorize(secondRequest, sharedContext)
            .subscribeOn(Schedulers.boundedElastic())
            .toFuture()
        CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS)

        events.single { it.request.path == "/first" }.authorization.source.policy?.id.assert()
            .isEqualTo("first-policy")
        events.single { it.request.path == "/second" }.authorization.source.policy?.id.assert()
            .isEqualTo("second-policy")
    }

    @Test
    fun publishTokenFailureReturnedToClient() {
        context.setTokenVerificationException(TokenExpiredException())
        val (authorization, events) = authorization(AuthorizeResult.IMPLICIT_DENY.toMono())
        authorization.authorize(request, context).block()
        events.single().apply {
            this.authorization.decision.assert().isEqualTo(AuditDecision.EXPLICIT_DENY)
            this.authorization.reason.assert().isEqualTo("Token Expired")
        }
    }

    @Test
    fun publishWhenTooManyRequestsAndErrorPropagates() {
        val (authorization, events) = authorization(TooManyRequestsException().toMono<AuthorizeResult>())
        authorization.authorize(request, context)
            .test()
            .expectError(TooManyRequestsException::class.java)
            .verify()
        events.size.assert().isEqualTo(1)
        events[0].authorization.decision.assert().isEqualTo(AuditDecision.TOO_MANY_REQUESTS)
    }

    @Test
    fun publishWhenUnexpectedErrorAndErrorPropagates() {
        val (authorization, events) = authorization(IllegalStateException().toMono<AuthorizeResult>())
        authorization.authorize(request, context)
            .test()
            .expectError(IllegalStateException::class.java)
            .verify()
        events[0].authorization.decision.assert().isEqualTo(AuditDecision.ERROR)
    }

    @Test
    fun publishWhenDelegateThrowsSynchronously() {
        val events = mutableListOf<AuditEvent>()
        val delegate = mockk<Authorization> {
            every { authorize(request, context) } throws TooManyRequestsException()
        }
        val authorization = AuditingAuthorization(delegate, AuditEventSink(events::add))
        authorization.authorize(request, context)
            .test()
            .expectError(TooManyRequestsException::class.java)
            .verify()
        events.single().authorization.decision.assert().isEqualTo(AuditDecision.TOO_MANY_REQUESTS)
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
            every { getHeader("User-Agent") } returns "test-agent"
            every { attributes } returns emptyMap()
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
        events[0].authorization.elapsedNanos.assert().isGreaterThanOrEqualTo(50_000_000)
    }

    @Test
    fun delegateIsWrapped() {
        val delegate = mockk<Authorization>()
        val authorization = AuditingAuthorization(delegate, AuditEventSink { })
        authorization.delegate.assert().isSameAs(delegate)
    }

    private fun requestForPath(path: String) = mockk<Request> {
        every { appId } returns "app"
        every { spaceId } returns "0"
        every { deviceId } returns ""
        every { requestId } returns path
        every { remoteIp } returns "1.2.3.4"
        every { method } returns "GET"
        every { this@mockk.path } returns path
        every { getHeader("User-Agent") } returns "test-agent"
        every { attributes } returns emptyMap()
    }
}
