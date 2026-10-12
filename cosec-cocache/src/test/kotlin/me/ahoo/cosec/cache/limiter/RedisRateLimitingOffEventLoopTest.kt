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

package me.ahoo.cosec.cache.limiter

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.limiter.RateLimit
import me.ahoo.cosec.limiter.RateLimitRule
import me.ahoo.cosec.limiter.RateLimitRuleProvider
import me.ahoo.cosec.limiter.RateLimitingAuthorization
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Proves that the Redis rate limiter never runs on a non-blocking thread, even when authorization answers
 * synchronously on it (the `LocalCacheLookup` hit path), and that its failure modes hold.
 *
 * `Schedulers.newParallel` threads implement `NonBlocking`, like the Netty event loop, so they stand in for it.
 */
class RedisRateLimitingOffEventLoopTest {
    companion object {
        private val eventLoop = Schedulers.newParallel("test-event-loop", 1)

        @AfterAll
        @JvmStatic
        fun dispose() {
            eventLoop.dispose()
        }
    }

    private val redisThreads = CopyOnWriteArrayList<Thread>()

    private fun redisTemplate(answer: () -> List<Long>): StringRedisTemplate = mockk {
        every { execute(any<RedisScript<List<Long>>>(), any<List<String>>(), *anyVararg()) } answers {
            redisThreads.add(Thread.currentThread())
            answer()
        }
    }

    private val allowAll: Authorization = object : DecisionAuthorization {
        override fun decide(
            request: me.ahoo.cosec.api.context.request.Request,
            context: me.ahoo.cosec.api.context.SecurityContext
        ): Mono<AuthorizationDecision> = AuthorizationDecision(AuthorizeResult.ALLOW).toMono()
    }

    private fun authorization(template: StringRedisTemplate, strictFailure: Boolean = false): Authorization {
        val rule = RateLimitRule(
            id = "rule",
            action = AllActionMatcher.INSTANCE,
            rateLimit = RateLimit(permitsPerSecond = 10.0),
            key = "request.remoteIp",
            strictFailure = strictFailure,
        )
        return RateLimitingAuthorization(allowAll, RateLimitRuleProvider.of(listOf(rule)), RedisRateLimiter(template))
    }

    private fun Authorization.authorizeOnEventLoop(): Mono<AuthorizeResult> =
        authorize(EvaluateRequest(), SimpleSecurityContext(SimplePrincipal("user"))).subscribeOn(eventLoop)

    private fun assertRedisRanOffEventLoop() {
        redisThreads.assert().isNotEmpty()
        redisThreads.forEach { thread ->
            Schedulers.isNonBlockingThread(thread).assert().isFalse()
            thread.name.assert().startsWith("boundedElastic")
        }
    }

    @Test
    fun allowed() {
        authorization(redisTemplate { listOf(1L, 0L) })
            .authorizeOnEventLoop()
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
        assertRedisRanOffEventLoop()
    }

    @Test
    fun rejectedIsTooManyRequests() {
        authorization(redisTemplate { listOf(0L, 10_000L) })
            .authorizeOnEventLoop()
            .test()
            .expectNext(AuthorizeResult.TOO_MANY_REQUESTS)
            .verifyComplete()
        assertRedisRanOffEventLoop()
    }

    @Test
    fun strictFailureFailsClosed() {
        authorization(redisTemplate { throw RedisConnectionFailureException("down") }, strictFailure = true)
            .authorizeOnEventLoop()
            .test()
            .expectNext(AuthorizeResult.TOO_MANY_REQUESTS)
            .verifyComplete()
        assertRedisRanOffEventLoop()
    }

    @Test
    fun nonStrictFailureFailsOpen() {
        authorization(redisTemplate { throw RedisConnectionFailureException("down") })
            .authorizeOnEventLoop()
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
        assertRedisRanOffEventLoop()
    }
}
