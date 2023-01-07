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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.policy.MATCHER_TYPE_KEY
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class BoolConditionMatcherTest {
    @Test
    fun matchWhenEmpty() {
        val conditionMatcher = BoolConditionMatcherFactory().create(JsonConfiguration.EMPTY)
        assertThat(conditionMatcher.type, `is`(BoolConditionMatcherFactory.TYPE))
        assertThat(conditionMatcher.configuration, `is`(JsonConfiguration.EMPTY))
        assertThat(conditionMatcher.match(mockk(), mockk()), `is`(true))
    }

    @Test
    fun matchWhenAndOneAll() {
        val conditionMatcher = BoolConditionMatcherFactory().create(
            mapOf(
                MATCHER_TYPE_KEY to BoolConditionMatcherFactory.TYPE,
                BoolConditionMatcherFactory.TYPE to mapOf<String, Any>(
                    BOOL_CONDITION_MATCHER_AND_KEY to listOf(
                        mapOf<String, Any>(
                            MATCHER_TYPE_KEY to AllConditionMatcherFactory.TYPE,
                        )
                    )
                )
            ).asConfiguration()
        ) as BoolConditionMatcher
        assertThat(conditionMatcher.type, `is`(BoolConditionMatcherFactory.TYPE))
        assertThat(conditionMatcher.and, hasSize(1))
        assertThat(conditionMatcher.and.first(), instanceOf(AllConditionMatcher::class.java))
        assertThat(conditionMatcher.or, empty())
        assertThat(conditionMatcher.match(mockk(), mockk()), `is`(true))
    }

    @Test
    fun matchWhenOrOneAll() {
        val conditionMatcher = BoolConditionMatcherFactory().create(
            mapOf(
                MATCHER_TYPE_KEY to BoolConditionMatcherFactory.TYPE,
                BoolConditionMatcherFactory.TYPE to mapOf<String, Any>(
                    BOOL_CONDITION_MATCHER_OR_KEY to listOf(
                        mapOf<String, Any>(
                            MATCHER_TYPE_KEY to AllConditionMatcherFactory.TYPE,
                        )
                    )
                )
            ).asConfiguration()
        ) as BoolConditionMatcher
        assertThat(conditionMatcher.type, `is`(BoolConditionMatcherFactory.TYPE))
        assertThat(conditionMatcher.or, hasSize(1))
        assertThat(conditionMatcher.or.first(), instanceOf(AllConditionMatcher::class.java))
        assertThat(conditionMatcher.and, empty())
        assertThat(conditionMatcher.match(mockk(), mockk()), `is`(true))
    }

    @Test
    fun matchWhenAndOneAllOrOneAll() {
        val conditionMatcher = BoolConditionMatcherFactory().create(
            mapOf(
                MATCHER_TYPE_KEY to BoolConditionMatcherFactory.TYPE,
                BoolConditionMatcherFactory.TYPE to mapOf<String, Any>(
                    BOOL_CONDITION_MATCHER_AND_KEY to listOf(
                        mapOf<String, Any>(
                            MATCHER_TYPE_KEY to AllConditionMatcherFactory.TYPE,
                        )
                    ),
                    BOOL_CONDITION_MATCHER_OR_KEY to listOf(
                        mapOf<String, Any>(
                            MATCHER_TYPE_KEY to AllConditionMatcherFactory.TYPE,
                        )
                    )
                )
            ).asConfiguration()
        ) as BoolConditionMatcher
        assertThat(conditionMatcher.type, `is`(BoolConditionMatcherFactory.TYPE))
        assertThat(conditionMatcher.and, hasSize(1))
        assertThat(conditionMatcher.and.first(), instanceOf(AllConditionMatcher::class.java))
        assertThat(conditionMatcher.or, hasSize(1))
        assertThat(conditionMatcher.or.first(), instanceOf(AllConditionMatcher::class.java))
        assertThat(conditionMatcher.match(mockk(), mockk()), `is`(true))
    }

    @Test
    fun matchGivenJson() {
        val testPolicy = requireNotNull(javaClass.classLoader.getResource("test-policy.json")).let { resource ->
            resource.openStream().use {
                CoSecJsonSerializer.readValue(it, Policy::class.java)
            }
        }

        val conditionMatcher = testPolicy.statements.first { it.name == "AllowDeveloperOrIpRange" }.conditions.first()
        assertThat(conditionMatcher, instanceOf(BoolConditionMatcher::class.java))
        val developerId = "developerId"
        val matchedContext: SecurityContext = mockk {
            every { principal.authenticated() } returns true
            every { principal.id } returns developerId
        }
        val notMatchedContext: SecurityContext = mockk {
            every { principal.authenticated() } returns true
            every { principal.id } returns "not-developerId"
        }
        val matchedRequest: Request = mockk {
            every { remoteIp } returns "192.168.0.1"
        }
        val notMatchedRequest: Request = mockk {
            every { remoteIp } returns "192.168.1.1"
        }

        assertThat(conditionMatcher.match(notMatchedRequest, matchedContext), `is`(true))
        assertThat(conditionMatcher.match(matchedRequest, notMatchedContext), `is`(true))
        assertThat(conditionMatcher.match(notMatchedRequest, notMatchedContext), `is`(false))
    }
}