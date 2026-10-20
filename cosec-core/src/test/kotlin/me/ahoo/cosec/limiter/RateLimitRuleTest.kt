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

package me.ahoo.cosec.limiter

import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.condition.context.AuthenticatedConditionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RateLimitRuleTest {
    private val rateLimit = RateLimit(permitsPerSecond = 1.0)

    @Test
    fun initWhenIdInvalid() {
        assertThrows(IllegalArgumentException::class.java) {
            RateLimitRule(id = "a:b", action = AllActionMatcher.INSTANCE, rateLimit = rateLimit)
        }
    }

    @Test
    fun initWhenKeyBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            RateLimitRule(id = "a", action = AllActionMatcher.INSTANCE, rateLimit = rateLimit, key = " ")
        }
    }

    @Test
    fun initWhenKeyUnsupported() {
        // Rejected when the rule loads, not with a 500 on every matching request.
        listOf("request.ip", "request.header.", "principal.id").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) {
                RateLimitRule(id = "a", action = AllActionMatcher.INSTANCE, rateLimit = rateLimit, key = key)
            }.message.assert().contains("key [$key]")
        }
    }

    @Test
    fun sharedBucket() {
        val rule = RateLimitRule(id = "a", action = AllActionMatcher.INSTANCE, rateLimit = rateLimit)
        rule.bucketOf(EvaluateRequest(), SimpleSecurityContext.anonymous()).assert().isEqualTo("global")
    }

    @Test
    fun keyedBucket() {
        val rule = RateLimitRule(
            id = "a",
            action = AllActionMatcher.INSTANCE,
            rateLimit = rateLimit,
            key = "request.remoteIp",
        )
        rule.bucketOf(EvaluateRequest(remoteIp = "1.1.1.1"), SimpleSecurityContext.anonymous())
            .assert().isEqualTo("g:1.1.1.1")
        // A key value named like the shared bucket cannot collide with it.
        rule.bucketOf(EvaluateRequest(remoteIp = "global"), SimpleSecurityContext.anonymous())
            .assert().isEqualTo("g:global")
    }

    @Test
    fun matches() {
        val rule = RateLimitRule(
            id = "a",
            action = AllActionMatcher.INSTANCE,
            condition = AuthenticatedConditionMatcher(JsonConfiguration.newPojoConfiguration()),
            rateLimit = rateLimit,
        )
        rule.matches(EvaluateRequest(), SimpleSecurityContext(SimplePrincipal("user"))).assert().isTrue()
        rule.matches(EvaluateRequest(), SimpleSecurityContext.anonymous()).assert().isFalse()
    }

    @Test
    fun providerRejectsDuplicateIds() {
        val rule = RateLimitRule(id = "a", action = AllActionMatcher.INSTANCE, rateLimit = rateLimit)
        assertThrows(IllegalArgumentException::class.java) {
            RateLimitRuleProvider.of(listOf(rule, rule))
        }
        RateLimitRuleProvider.of(listOf(rule)).getRules().assert().containsExactly(rule)
    }
}
