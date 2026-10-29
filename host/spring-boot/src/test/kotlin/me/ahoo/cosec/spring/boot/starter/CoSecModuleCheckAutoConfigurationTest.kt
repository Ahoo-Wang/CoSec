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

package me.ahoo.cosec.spring.boot.starter

import io.mockk.mockk
import me.ahoo.cosec.spring.boot.starter.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.AbstractApplicationContextRunner
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

/** `STA-1`: a feature that needs a module fails startup without it, and names the artifact to add. */
class CoSecModuleCheckAutoConfigurationTest {
    private val config = AutoConfigurations.of(CoSecModuleCheckAutoConfiguration::class.java)
    private val redis = { mockk<ReactiveStringRedisTemplate>(relaxed = true) }
    private val plain = ApplicationContextRunner().withConfiguration(config)
        .withBean(ReactiveStringRedisTemplate::class.java, redis)
    private val servlet = WebApplicationContextRunner().withConfiguration(config)
        .withBean(ReactiveStringRedisTemplate::class.java, redis)
    private val reactive = ReactiveWebApplicationContextRunner().withConfiguration(config)
        .withBean(ReactiveStringRedisTemplate::class.java, redis)

    private fun failsNaming(runner: AbstractApplicationContextRunner<*, *, *>, artifact: String) {
        runner.run { context ->
            val failure = context.startupFailure
            failure.assert().isNotNull()
            val named = generateSequence(failure) { it.cause }
                .any { "$artifact (me.ahoo.cosec:$artifact)" in it.message.orEmpty() }
            named.assert().describedAs(failure.toString()).isTrue()
        }
    }

    private fun starts(runner: AbstractApplicationContextRunner<*, *, *>) {
        runner.run { context -> context.startupFailure.assert().isNull() }
    }

    @Test
    fun `a Spring MVC host without cosec-webmvc fails startup`() {
        failsNaming(
            servlet.withClassLoader(FilteredClassLoader("me.ahoo.cosec.servlet.PipelineServletFilter")),
            "cosec-webmvc",
        )
    }

    @Test
    fun `a Spring MVC host with cosec-webmvc, or with CoSec off, starts`() {
        starts(servlet)
        starts(
            servlet.withClassLoader(FilteredClassLoader("me.ahoo.cosec.servlet.PipelineServletFilter"))
                .withPropertyValues("cosec.enabled=false")
        )
    }

    @Test
    fun `a WebFlux host without cosec-webflux fails startup`() {
        failsNaming(
            reactive.withClassLoader(
                FilteredClassLoader(GATEWAY_GLOBAL_FILTER, "me.ahoo.cosec.webflux.PipelineWebFilter")
            ),
            "cosec-webflux",
        )
    }

    @Test
    fun `a WebFlux host with cosec-webflux starts`() {
        starts(reactive.withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER)))
    }

    @Test
    fun `a Spring Cloud Gateway host without cosec-gateway fails startup, even with CoSec off`() {
        failsNaming(
            reactive.withClassLoader(FilteredClassLoader("me.ahoo.cosec.gateway.IdentityStripFilter"))
                .withPropertyValues("cosec.enabled=false"),
            "cosec-gateway",
        )
    }

    @Test
    fun `a Spring Cloud Gateway host with cosec-gateway starts`() {
        starts(reactive)
    }

    @Test
    fun `Redis API keys without cosec-cocache fail startup`() {
        failsNaming(
            plain.withClassLoader(FilteredClassLoader("me.ahoo.cosec.cache.RedisApiKeyStore"))
                .withPropertyValues("cosec.api-key.enabled=true", "cosec.api-key.store=redis"),
            "cosec-cocache",
        )
    }

    @Test
    fun `API keys in memory, or turned off, need no cosec-cocache`() {
        val noCache = plain.withClassLoader(FilteredClassLoader("me.ahoo.cosec.cache.RedisApiKeyStore"))
        starts(noCache.withPropertyValues("cosec.api-key.enabled=true", "cosec.api-key.store=memory"))
        starts(noCache.withPropertyValues("cosec.api-key.enabled=false", "cosec.api-key.store=redis"))
    }

    @Test
    fun `a CosId generator for CoSec's ids without cosec-cosid fails startup`() {
        failsNaming(
            plain.withClassLoader(FilteredClassLoader("me.ahoo.cosec.cosid.CosIdIdGenerator"))
                .withPropertyValues("cosec.cosid.generator=order"),
            "cosec-cosid",
        )
        // Without the key, CoSec's own ids need no cosec-cosid.
        starts(plain.withClassLoader(FilteredClassLoader("me.ahoo.cosec.cosid.CosIdIdGenerator")))
    }

    @Test
    fun `a required Kafka audit without cosec-kafka fails startup`() {
        failsNaming(
            plain.withClassLoader(FilteredClassLoader("me.ahoo.cosec.kafka.KafkaAuditListener"))
                .withPropertyValues("cosec.audit.kafka.delivery=required"),
            "cosec-kafka",
        )
        // The listener's own parse: case and surrounding spaces do not matter.
        failsNaming(
            plain.withClassLoader(FilteredClassLoader("me.ahoo.cosec.kafka.KafkaAuditListener"))
                .withPropertyValues("cosec.audit.kafka.delivery= REQUIRED "),
            "cosec-kafka",
        )
    }

    @Test
    fun `a best-effort Kafka audit needs no cosec-kafka`() {
        starts(
            plain.withClassLoader(FilteredClassLoader("me.ahoo.cosec.kafka.KafkaAuditListener"))
                .withPropertyValues("cosec.audit.kafka.delivery=best_effort")
        )
    }

    @Test
    fun `cosec-cocache without a Redis template fails startup`() {
        val noRedis = ApplicationContextRunner().withConfiguration(config)
        noRedis.run { context ->
            val failure = context.startupFailure
            failure.assert().isNotNull()
            generateSequence(failure) { it.cause }.any { "spring-boot-data-redis" in it.message.orEmpty() }
                .assert().describedAs(failure.toString()).isTrue()
        }
    }

    @Test
    fun `without cosec-cocache, or with CoSec off, no Redis template is needed`() {
        val noRedis = ApplicationContextRunner().withConfiguration(config)
        starts(noRedis.withClassLoader(FilteredClassLoader("me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore")))
        starts(noRedis.withPropertyValues("cosec.enabled=false"))
        starts(plain)
    }
}
