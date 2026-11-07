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

package me.ahoo.cosec.spring.boot.starter.pipeline

import io.lettuce.core.ReadFrom
import io.mockk.mockk
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.starter.apikey.CoSecApiKeyAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory

/**
 * RC1-47 (b): Redis is CoSec's store of record, so the Lettuce client behind its Redis templates reads the primary. One
 * set to read from replicas, by `spring.data.redis.lettuce.read-from` or a customizer, fails startup.
 */
class ReplicaReadsTest {
    private fun factory(readFrom: ReadFrom?) = LettuceConnectionFactory(
        RedisStandaloneConfiguration(),
        LettuceClientConfiguration.builder().apply { readFrom?.let { readFrom(it) } }.build(),
    )

    @Test
    fun `a template that reads from replicas is refused`() {
        for (replicas in listOf(
            ReadFrom.REPLICA_PREFERRED,
            ReadFrom.REPLICA,
            ReadFrom.ANY,
            ReadFrom.ANY_REPLICA,
            ReadFrom.LOWEST_LATENCY,
            ReadFrom.UPSTREAM_PREFERRED
        )) {
            assertThrows<IllegalStateException> { ReplicaReads.check(listOf(factory(null), factory(replicas))) }
                .message.assert().contains("reads from replicas").contains("spring.data.redis.lettuce.read-from")
        }
        // The upstream, by either name, or no ReadFrom at all; a factory this check cannot see into is storage.md's.
        ReplicaReads.check(
            listOf(factory(null), factory(ReadFrom.UPSTREAM), factory(ReadFrom.MASTER), mockk<RedisConnectionFactory>())
        )
    }

    private val runner = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                DataRedisAutoConfiguration::class.java,
                DataRedisReactiveAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
                CoSecApiKeyAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            "cosec.authorization.routes.location=",
        )
        .withBean(TokenStore::class.java, { TokenStore.NoOp })
        .withBean(RevocationReader::class.java, { RevocationReader.NONE })

    @Test
    fun `replica reads fail startup, naming the property`() {
        runner.withPropertyValues("spring.data.redis.lettuce.read-from=replicaPreferred").run { context ->
            val message = generateSequence(context.startupFailure) { it.cause }.mapNotNull { it.message }.joinToString()
            message.assert().contains("reads from replicas").contains("spring.data.redis.lettuce.read-from")
        }
        runner.withPropertyValues("spring.data.redis.lettuce.read-from=upstream").run { context ->
            context.startupFailure.assert().isNull()
        }
        runner.run { context -> context.startupFailure.assert().isNull() }
    }
}
