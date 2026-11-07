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
import org.springframework.data.redis.core.StringRedisTemplate

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
            assertThrows<IllegalStateException> {
                ReplicaReads.check(listOf("upstream" to factory(null), "replica" to factory(replicas)))
            }.message.assert().contains("[replica] (").contains("reads from replicas")
                .contains("spring.data.redis.lettuce.read-from")
        }
        // The upstream, by either name, or no ReadFrom at all; a factory this check cannot see into is storage.md's.
        ReplicaReads.check(
            listOf(
                "none" to factory(null),
                "upstream" to factory(ReadFrom.UPSTREAM),
                "master" to factory(ReadFrom.MASTER),
                "other" to mockk<RedisConnectionFactory>(),
                "absent" to null,
            )
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

    /**
     * R2-L1 of #1240's review: with two templates and no primary, an injection point resolves by parameter name, which the
     * check does not follow, so every one is checked: the ones that read from replicas fail startup, named.
     */
    @Test
    fun `without a unique or primary template every one is checked`() {
        fun template(readFrom: ReadFrom?) = StringRedisTemplate(factory(readFrom))
        runner
            .withBean("stringRedisTemplate", StringRedisTemplate::class.java, { template(ReadFrom.REPLICA_PREFERRED) })
            .withBean("otherTemplate", StringRedisTemplate::class.java, { template(null) })
            .run { context ->
                val message = generateSequence(context.startupFailure) { it.cause }.mapNotNull { it.message }.joinToString()
                message.assert().contains("[stringRedisTemplate] (ReadFromReplicaPreferred)")
                    .doesNotContain("[otherTemplate]")
            }
        runner
            .withBean("stringRedisTemplate", StringRedisTemplate::class.java, { template(null) })
            .withBean("otherTemplate", StringRedisTemplate::class.java, { template(ReadFrom.UPSTREAM) })
            .run { context -> context.startupFailure.assert().isNull() }
    }

    /**
     * Review L4 of #1240: only the template CoSec injects, the only one or the @Primary one, is checked; an application's
     * other template may read from replicas. The message names the bean.
     */
    @Test
    fun `only the template CoSec injects is checked, by its name`() {
        fun template(readFrom: ReadFrom?) = StringRedisTemplate(factory(readFrom))
        runner
            .withBean("cosecTemplate", StringRedisTemplate::class.java, { template(null) }, { it.isPrimary = true })
            .withBean("replicaTemplate", StringRedisTemplate::class.java, { template(ReadFrom.REPLICA_PREFERRED) })
            .run { context -> context.startupFailure.assert().isNull() }
        runner
            .withBean("upstreamTemplate", StringRedisTemplate::class.java, { template(null) })
            .withBean("replicaTemplate", StringRedisTemplate::class.java, { template(ReadFrom.REPLICA_PREFERRED) }, {
                it.isPrimary = true
            })
            .run { context ->
                val message = generateSequence(
                    context.startupFailure
                ) { it.cause }.mapNotNull { it.message }.joinToString()
                message.assert().contains("[replicaTemplate] (ReadFromReplicaPreferred) reads from replicas")
            }
    }
}
