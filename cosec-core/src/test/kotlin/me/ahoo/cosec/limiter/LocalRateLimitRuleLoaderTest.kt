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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LocalRateLimitRuleLoaderTest {
    @Test
    fun loadObjectsAndArrays() {
        val rules = LocalRateLimitRuleLoader(setOf("classpath:cosec-rate-limit/*-rate-limit.json")).getRules()
        rules.map { it.id }.assert().containsExactlyInAnyOrder("login-by-ip", "export-free-tier", "global-api")
        val login = rules.first { it.id == "login-by-ip" }
        login.key.assert().isEqualTo("request.remoteIp")
        login.rateLimit.assert().isEqualTo(RateLimit(permitsPerSecond = 0.1, windowSeconds = 60))
        login.strictFailure.assert().isTrue()
    }

    @Test
    fun missingLocationLoadsNothing() {
        LocalRateLimitRuleLoader(setOf("classpath:not-exists/*.json")).getRules().assert().isEmpty()
    }

    @Test
    fun invalidRuleFailsFast() {
        assertThrows(IllegalStateException::class.java) {
            LocalRateLimitRuleLoader(setOf("classpath:cosec-rate-limit/invalid/*.json")).getRules()
        }
    }

    @Test
    fun duplicateIdsFailFast() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalRateLimitRuleLoader(setOf("classpath:cosec-rate-limit/duplicate/*.json")).getRules()
        }
    }
}
