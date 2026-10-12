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

package me.ahoo.cosec.policy.condition

import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TimeConditionMatcherTest {
    private fun matcher(configuration: Map<String, Any>, now: String): ConditionMatcher =
        TimeConditionMatcher(configuration.asConfiguration(), Clock.fixed(Instant.parse(now), ZoneOffset.UTC))

    private fun ConditionMatcher.matches(): Boolean = match(EvaluateRequest(), SimpleSecurityContext.anonymous())

    @Test
    fun validityPeriod() {
        val period = mapOf("notBefore" to "2026-10-01T00:00:00Z", "notAfter" to "2026-11-01T00:00:00+08:00")
        matcher(period, "2026-09-30T23:59:59Z").matches().assert().isFalse()
        matcher(period, "2026-10-01T00:00:00Z").matches().assert().isTrue()
        // notAfter is exclusive: 2026-11-01T00:00+08:00 is 2026-10-31T16:00Z.
        matcher(period, "2026-10-31T15:59:59Z").matches().assert().isTrue()
        matcher(period, "2026-10-31T16:00:00Z").matches().assert().isFalse()
    }

    @Test
    fun offsetLessDateTimeUsesZone() {
        val expiry = mapOf("notAfter" to "2026-11-01T00:00:00", "zone" to "Asia/Shanghai")
        matcher(expiry, "2026-10-31T15:59:59Z").matches().assert().isTrue()
        matcher(expiry, "2026-10-31T16:00:00Z").matches().assert().isFalse()
    }

    @Test
    fun businessHours() {
        val hours = mapOf(
            "daysOfWeek" to listOf("MON", "tuesday", "WED", "THU", "FRI"),
            "from" to "09:00",
            "to" to "18:00",
            "zone" to "Asia/Shanghai",
        )
        // 2026-10-12 is a Monday. 01:00Z is 09:00 in Shanghai.
        matcher(hours, "2026-10-12T01:00:00Z").matches().assert().isTrue()
        matcher(hours, "2026-10-12T00:59:59Z").matches().assert().isFalse()
        matcher(hours, "2026-10-12T10:00:00Z").matches().assert().isFalse()
        // Saturday at noon.
        matcher(hours, "2026-10-17T04:00:00Z").matches().assert().isFalse()
    }

    @Test
    fun windowAcrossMidnightBelongsToStartDay() {
        val night = mapOf("daysOfWeek" to listOf("FRIDAY"), "from" to "22:00", "to" to "06:00")
        // Friday 2026-10-16 23:00 and Saturday 05:00 are both in Friday's window.
        matcher(night, "2026-10-16T23:00:00Z").matches().assert().isTrue()
        matcher(night, "2026-10-17T05:00:00Z").matches().assert().isTrue()
        matcher(night, "2026-10-17T06:00:00Z").matches().assert().isFalse()
        // Thursday night is not Friday's window, and Friday early morning belongs to Thursday.
        matcher(night, "2026-10-15T23:00:00Z").matches().assert().isFalse()
        matcher(night, "2026-10-16T05:00:00Z").matches().assert().isFalse()
    }

    @Test
    fun negate() {
        val outsideHours = mapOf("from" to "09:00", "to" to "18:00", "negate" to true)
        matcher(outsideHours, "2026-10-12T20:00:00Z").matches().assert().isTrue()
        matcher(outsideHours, "2026-10-12T10:00:00Z").matches().assert().isFalse()
    }

    @Test
    fun invalidConfigurations() {
        listOf(
            mapOf<String, Any>(),
            mapOf("from" to "09:00"),
            mapOf("from" to "09:00", "to" to "09:00"),
            mapOf("notBefore" to "2026-11-01T00:00:00Z", "notAfter" to "2026-10-01T00:00:00Z"),
            mapOf("daysOfWeek" to listOf("FUNDAY")),
            mapOf("daysOfWeek" to emptyList<String>()),
        ).forEach { configuration ->
            assertThrows(IllegalArgumentException::class.java) {
                matcher(configuration, "2026-10-12T00:00:00Z")
            }
        }
    }

    @Test
    fun fromPolicyJson() {
        val condition = CoSecJsonSerializer.readValue(
            """{"time":{"notAfter":"2999-01-01T00:00:00Z"}}""",
            ConditionMatcher::class.java,
        )
        condition.type.assert().isEqualTo(TimeConditionMatcherFactory.TYPE)
        condition.matches().assert().isTrue()
    }
}
