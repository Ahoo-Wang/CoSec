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
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import me.ahoo.cosec.policy.condition.limiter.RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY
import me.ahoo.cosec.policy.condition.limiter.TooManyRequestsException
import me.ahoo.cosec.policy.condition.part.CONDITION_MATCHER_PART_KEY
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Proves that a Redis rate limiter is evaluated off a non-blocking thread even when the repositories answer
 * synchronously (the `LocalCacheLookup` hit path), while policies without one are evaluated in place.
 *
 * `Schedulers.newParallel` threads implement `NonBlocking`, like the Netty event loop, so they stand in for it.
 */
class RedisRateLimiterOffEventLoopTest {
    companion object {
        private val eventLoop = Schedulers.newParallel("test-event-loop", 1)

        @AfterAll
        @JvmStatic
        fun dispose() {
            eventLoop.dispose()
        }
    }

    private val redisThreads = CopyOnWriteArrayList<Thread>()

    private fun redisTemplate(answer: () -> Long): StringRedisTemplate = mockk {
        every { execute(any<RedisScript<Long>>(), any<List<String>>(), *anyVararg()) } answers {
            redisThreads.add(Thread.currentThread())
            answer()
        }
    }

    private fun limiter(
        template: StringRedisTemplate,
        strictFailure: Boolean = false
    ): ConditionMatcher = RedisRateLimiterConditionMatcherFactory(template).create(
        mapOf(
            RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY to 10,
            REDIS_RATE_LIMITER_CONDITION_MATCHER_STRICT_FAILURE_KEY to strictFailure,
        ).asConfiguration(),
    )

    private fun groupedLimiter(template: StringRedisTemplate): ConditionMatcher =
        RedisGroupedRateLimiterConditionMatcherFactory(template).create(
            mapOf(
                RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY to 10,
                CONDITION_MATCHER_PART_KEY to "request.remoteIp",
            ).asConfiguration(),
        )

    private fun policy(
        condition: ConditionMatcher = AllConditionMatcher.INSTANCE,
        statementCondition: ConditionMatcher = AllConditionMatcher.INSTANCE
    ): Policy = PolicyData(
        id = "policy",
        category = "",
        name = "",
        description = "",
        type = PolicyType.GLOBAL,
        tenantId = "",
        condition = condition,
        statements = listOf(
            StatementData(effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE, condition = statementCondition),
        ),
    )

    private fun authorization(
        globalPolicies: List<Policy> = emptyList(),
        principalPolicies: List<Policy> = emptyList(),
        permissionCondition: ConditionMatcher? = null
    ): SimpleAuthorization {
        // Both repositories answer synchronously, as a local-cache hit does: no scheduler hop of their own.
        val policyRepository = object : PolicyRepository {
            override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(globalPolicies)
            override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.just(principalPolicies)
            override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
        }
        val appRolePermissionRepository = object : AppRolePermissionRepository {
            override fun getAppRolePermission(
                appId: String,
                spaceId: String,
                roleIds: Set<RoleId>
            ): Mono<AppRolePermission> {
                if (permissionCondition == null) {
                    return Mono.empty()
                }
                val permission = PermissionData(
                    id = "p",
                    name = "p",
                    action = AllActionMatcher.INSTANCE,
                    condition = permissionCondition,
                )
                val appPermission = AppPermissionData(
                    id = appId,
                    groups = listOf(PermissionGroupData("g", permissions = listOf(permission))),
                )
                return Mono.just(AppRolePermissionData(appPermission, listOf(RolePermissionData("role", setOf("p")))))
            }
        }
        return SimpleAuthorization(policyRepository, appRolePermissionRepository)
    }

    private fun SimpleAuthorization.authorizeOnEventLoop(): Mono<AuthorizeResult> {
        val principal = SimplePrincipal(id = "user", policies = setOf("policy"), roles = setOf("role"))
        // Delegating keeps EvaluateRequest's defaults without being one, which the limiters treat as validation.
        val request = object : Request by EvaluateRequest() {}
        return authorize(request, SimpleSecurityContext(principal)).subscribeOn(eventLoop)
    }

    private fun assertRedisRanOffEventLoop() {
        redisThreads.assert().isNotEmpty()
        redisThreads.forEach { thread ->
            Schedulers.isNonBlockingThread(thread).assert().isFalse()
            thread.name.assert().startsWith("boundedElastic")
        }
    }

    @Test
    fun globalPolicyCondition() {
        authorization(globalPolicies = listOf(policy(condition = limiter(redisTemplate { 1L }))))
            .authorizeOnEventLoop()
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
        assertRedisRanOffEventLoop()
    }

    @Test
    fun principalPolicyStatementCondition() {
        authorization(principalPolicies = listOf(policy(statementCondition = groupedLimiter(redisTemplate { 1L }))))
            .authorizeOnEventLoop()
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
        assertRedisRanOffEventLoop()
    }

    @Test
    fun rolePermissionCondition() {
        authorization(permissionCondition = limiter(redisTemplate { 1L }))
            .authorizeOnEventLoop()
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
        assertRedisRanOffEventLoop()
    }

    @Test
    fun rejectedSurfacesTooManyRequests() {
        authorization(globalPolicies = listOf(policy(condition = limiter(redisTemplate { 0L }))))
            .authorizeOnEventLoop()
            .test()
            .expectError(TooManyRequestsException::class.java)
            .verify()
        assertRedisRanOffEventLoop()
    }

    @Test
    fun strictFailureFailsClosed() {
        val template = redisTemplate { throw RedisConnectionFailureException("down") }
        authorization(globalPolicies = listOf(policy(condition = limiter(template, strictFailure = true))))
            .authorizeOnEventLoop()
            .test()
            .expectError(TooManyRequestsException::class.java)
            .verify()
        assertRedisRanOffEventLoop()
    }

    @Test
    fun nonStrictFailureFailsOpen() {
        val template = redisTemplate { throw RedisConnectionFailureException("down") }
        authorization(globalPolicies = listOf(policy(condition = limiter(template))))
            .authorizeOnEventLoop()
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
        assertRedisRanOffEventLoop()
    }

    @Test
    fun policyWithoutBlockingMatcherStaysInPlace() {
        val matchThreads = CopyOnWriteArrayList<Thread>()
        val recording = object : ConditionMatcher {
            override val type: String = "recording"
            override val configuration: Configuration = JsonConfiguration.NULL
            override fun match(request: Request, securityContext: SecurityContext): Boolean {
                matchThreads.add(Thread.currentThread())
                return true
            }
        }
        authorization(globalPolicies = listOf(policy(condition = recording)))
            .authorizeOnEventLoop()
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
        matchThreads.assert().hasSize(1)
        matchThreads.first().name.assert().startsWith("test-event-loop")
    }
}
