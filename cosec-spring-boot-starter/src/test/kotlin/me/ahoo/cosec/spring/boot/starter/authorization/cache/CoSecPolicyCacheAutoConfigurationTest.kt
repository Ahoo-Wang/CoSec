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

package me.ahoo.cosec.spring.boot.starter.authorization.cache

import me.ahoo.cache.spring.boot.starter.CoCacheAutoConfiguration
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.cache.GlobalPolicyIndexCache
import me.ahoo.cosec.cache.PolicyCache
import me.ahoo.cosec.cache.RedisPolicyRepository
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.history.PolicyHistory
import me.ahoo.cosec.policy.history.PolicyRevisions
import me.ahoo.cosec.policy.history.VersionedPolicyRepository
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.test.MockIdGenerator
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class CoSecPolicyCacheAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withBean(IdGenerator::class.java, { MockIdGenerator.INSTANCE })
            .withUserConfiguration(
                JacksonAutoConfiguration::class.java,
                DataRedisAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecPolicyCacheAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                assertThat(context)
                    .hasSingleBean(CacheProperties::class.java)
                    .hasSingleBean(CoSecPolicyCacheAutoConfiguration::class.java)
                    .hasSingleBean(GlobalPolicyIndexCache::class.java)
                    .hasBean(CoSecPolicyCacheAutoConfiguration.POLICY_CACHE_BEAN_NAME)
                    .hasSingleBean(PolicyCache::class.java)
                    .hasSingleBean(PolicyRepository::class.java)
            }
    }

    @Test
    fun recordsPolicyHistoryWhenEnabled() {
        val policyId = "history-${java.util.UUID.randomUUID()}"
        contextRunner
            .withBean(IdGenerator::class.java, { MockIdGenerator.INSTANCE })
            .withPropertyValues(
                "${CoSecPolicyCacheAutoConfiguration.POLICY_HISTORY_ENABLED_KEY}=true",
                "cosec.authorization.cache.history.max-revisions=5",
            )
            .withUserConfiguration(
                JacksonAutoConfiguration::class.java,
                DataRedisAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecPolicyCacheAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                assertThat(context).hasNotFailed().hasSingleBean(PolicyHistory::class.java)
                val repository = context.getBean(PolicyRepository::class.java)
                assertThat(repository).isInstanceOf(VersionedPolicyRepository::class.java)
                val policy = PolicyData(policyId, "", "v1", "", PolicyType.CUSTOM, "t")
                repository.setPolicy(policy).contextWrite(PolicyRevisions.author("alice")).block()
                val revision = checkNotNull(
                    context.getBean(PolicyHistory::class.java).history(policyId).block()
                ).single()
                revision.author.assert().isEqualTo("alice")
                revision.policy.name.assert().isEqualTo("v1")
            }
    }

    @Test
    fun noHistoryByDefault() {
        contextRunner
            .withBean(IdGenerator::class.java, { MockIdGenerator.INSTANCE })
            .withUserConfiguration(
                JacksonAutoConfiguration::class.java,
                DataRedisAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecPolicyCacheAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                assertThat(context).doesNotHaveBean(PolicyHistory::class.java)
                assertThat(
                    context.getBean(PolicyRepository::class.java)
                ).isInstanceOf(RedisPolicyRepository::class.java)
            }
    }
}
