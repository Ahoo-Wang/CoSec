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

import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.CoSecCoCache5Migration
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosid.test.MockIdGenerator
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate

class CoSecCacheMigrationAutoConfigurationTest {
    private val keyPrefix = "test:" + MockIdGenerator.INSTANCE.generateAsString()
    private val contextRunner = ApplicationContextRunner()
        .withPropertyValues(
            "${CacheProperties.PREFIX}.key-prefix=$keyPrefix",
            "${CacheProperties.PREFIX}.migration.backup-prefix=$keyPrefix-backup:",
        )
        .withUserConfiguration(
            JacksonAutoConfiguration::class.java,
            DataRedisAutoConfiguration::class.java,
            CoCacheAutoConfiguration::class.java,
            CoSecPolicyCacheAutoConfiguration::class.java,
            CoSecPermissionCacheAutoConfiguration::class.java,
            CoSecCacheMigrationAutoConfiguration::class.java,
        )

    @Test
    fun migratesWithTheApplicationsRedisClient() {
        val policy = PolicyData("p1", "c", "n", "", PolicyType.CUSTOM, "t")
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            val redis = context.getBean(StringRedisTemplate::class.java)
            try {
                redis.opsForValue().set("$keyPrefix:policy:p1", CoSecJsonSerializer.writeValueAsString(policy))
                val migration = context.getBean(CoSecCoCache5Migration::class.java)
                migration.dryRun().cache(CoSecCacheSpecs.POLICY_CACHE_NAME)!!.written.assert().isEqualTo(1L)
                migration.migrate()
                context.getBean(PolicyRepository::class.java).getPolicies(setOf("p1")).block()
                    .assert().isEqualTo(listOf(policy))
            } finally {
                redis.delete(redis.keys("$keyPrefix*"))
            }
        }
    }

    @Test
    fun noMigrationWithoutRedis() {
        ApplicationContextRunner()
            .withPropertyValues("cocache.backend=memory")
            .withUserConfiguration(
                JacksonAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecCacheMigrationAutoConfiguration::class.java,
            )
            .run { context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(CoSecCoCache5Migration::class.java)
            }
    }
}
