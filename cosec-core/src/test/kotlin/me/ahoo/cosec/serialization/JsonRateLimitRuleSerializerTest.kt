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

package me.ahoo.cosec.serialization

import me.ahoo.cosec.limiter.RateLimit
import me.ahoo.cosec.limiter.RateLimitRule
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JsonRateLimitRuleSerializerTest {
    @Test
    fun roundTrip() {
        val json = """
{"id":"login","action":"/auth/login","condition":{"authenticated":{}},"key":"request.remoteIp","permitsPerSecond":0.5,"windowSeconds":60,"strictFailure":true}
        """.trim()
        val rule = CoSecJsonSerializer.readValue(json, RateLimitRule::class.java)
        rule.id.assert().isEqualTo("login")
        rule.key.assert().isEqualTo("request.remoteIp")
        rule.rateLimit.assert().isEqualTo(RateLimit(permitsPerSecond = 0.5, windowSeconds = 60))
        rule.strictFailure.assert().isTrue()
        val reread = CoSecJsonSerializer.readValue(
            CoSecJsonSerializer.writeValueAsString(rule),
            RateLimitRule::class.java,
        )
        reread.id.assert().isEqualTo(rule.id)
        reread.condition.type.assert().isEqualTo("authenticated")
        reread.rateLimit.assert().isEqualTo(rule.rateLimit)
    }

    @Test
    fun defaults() {
        val rule = CoSecJsonSerializer.readValue(
            """{"id":"api","action":"*","permitsPerSecond":10}""",
            RateLimitRule::class.java,
        )
        rule.key.assert().isNull()
        rule.condition.type.assert().isEqualTo(AllConditionMatcher.INSTANCE.type)
        rule.rateLimit.windowSeconds.assert().isEqualTo(1)
        rule.strictFailure.assert().isFalse()
        CoSecJsonSerializer.writeValueAsString(rule).assert().doesNotContain("\"key\"")
    }

    @Test
    fun missingRequiredFields() {
        listOf(
            """{"action":"*","permitsPerSecond":10}""",
            """{"id":"api","permitsPerSecond":10}""",
            """{"id":"api","action":"*"}""",
        ).forEach { json ->
            assertThrows(IllegalArgumentException::class.java) {
                CoSecJsonSerializer.readValue(json, RateLimitRule::class.java)
            }
        }
    }
}
