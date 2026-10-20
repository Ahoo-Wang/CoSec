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
import me.ahoo.cosec.cache.CoSecCoCache5Migration
import me.ahoo.cosec.cache.CoSecSchemaException
import me.ahoo.cosec.cache.CoSecSchemaMarker
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosid.test.MockIdGenerator
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * The starter checks the schema marker before it creates any CoSec cache, against real Redis.
 */
class CoSecCacheSchemaAutoConfigurationTest {
    private val keyPrefix = "test:" + MockIdGenerator.INSTANCE.generateAsString()
    private val connectionFactory =
        LettuceConnectionFactory(RedisStandaloneConfiguration()).apply { afterPropertiesSet() }
    private val redis = StringRedisTemplate(connectionFactory)
    private val contextRunner = ApplicationContextRunner()
        .withPropertyValues(
            "${CacheProperties.PREFIX}.key-prefix=$keyPrefix:",
            "${CacheProperties.PREFIX}.migration.backup-prefix=$keyPrefix-backup:",
        )
        .withUserConfiguration(
            JacksonAutoConfiguration::class.java,
            DataRedisAutoConfiguration::class.java,
            CoCacheAutoConfiguration::class.java,
            CoSecCacheSchemaAutoConfiguration::class.java,
            CoSecPolicyCacheAutoConfiguration::class.java,
            CoSecPermissionCacheAutoConfiguration::class.java,
            CoSecCacheMigrationAutoConfiguration::class.java,
        )

    @AfterEach
    fun destroy() {
        redis.delete(redis.keys("$keyPrefix*"))
        connectionFactory.destroy()
    }

    @Test
    fun unmigratedDataStopsStartupUntilMigrated() {
        // a key prefix ending with ':': 6.x stored "p::policy:x", which 7.0 never reads
        val deny = PolicyData("deny", "c", "n", "", PolicyType.GLOBAL, "t")
        redis.opsForValue().set("$keyPrefix::policy:deny", CoSecJsonSerializer.writeValueAsString(deny))
        redis.opsForSet().add("$keyPrefix::global:policy", "deny")

        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().isInstanceOf(CoSecSchemaException::class.java)
        }

        // the migration job turns the check off, migrates, and writes the marker
        contextRunner.withPropertyValues("${MigrationConfiguration.CHECK_SCHEMA_KEY}=false").run { context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(CoSecSchemaCheck::class.java)
            context.getBean(CoSecCoCache5Migration::class.java).migrate()
        }

        contextRunner.run { context ->
            assertThat(context).hasNotFailed().hasSingleBean(CoSecSchemaCheck::class.java)
            context.getBean(PolicyRepository::class.java).getGlobalPolicy().block().assert().isEqualTo(listOf(deny))
        }
    }

    @Test
    fun newDeploymentIsMarked() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            context.getBean(CoSecSchemaCheck::class.java).marker.isMarked().assert().isTrue()
        }
        redis.opsForValue().get("$keyPrefix:meta:schema").assert().isEqualTo(CoSecSchemaMarker.SCHEMA_VERSION)
    }

    @Test
    fun noCheckWithTheMemoryBackend() {
        contextRunner.withPropertyValues("cocache.backend=memory").run { context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(CoSecSchemaCheck::class.java)
        }
    }
}
