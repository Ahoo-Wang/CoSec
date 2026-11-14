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

package me.ahoo.cosec.spring.boot

import io.mockk.mockk
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cosec.spring.boot.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
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
    private val runtime = { mockk<CacheRuntime>(relaxed = true) }
    private val plain = ApplicationContextRunner().withConfiguration(config)
        .withBean(ReactiveStringRedisTemplate::class.java, redis)
        .withBean(CacheRuntime::class.java, runtime)
    private val servlet = WebApplicationContextRunner().withConfiguration(config)
        .withBean(ReactiveStringRedisTemplate::class.java, redis)
        .withBean(CacheRuntime::class.java, runtime)
    private val reactive = ReactiveWebApplicationContextRunner().withConfiguration(config)
        .withBean(ReactiveStringRedisTemplate::class.java, redis)
        .withBean(CacheRuntime::class.java, runtime)

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
            servlet.withClassLoader(FilteredClassLoader("me.ahoo.cosec.webmvc.PipelineServletFilter")),
            "cosec-webmvc",
        )
    }

    @Test
    fun `a Spring MVC host with cosec-webmvc, or with CoSec off, starts`() {
        starts(servlet)
        starts(
            servlet.withClassLoader(FilteredClassLoader("me.ahoo.cosec.webmvc.PipelineServletFilter"))
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
            plain.withClassLoader(FilteredClassLoader("me.ahoo.cosec.cocache.RedisApiKeyStore"))
                .withPropertyValues("cosec.api-key.enabled=true", "cosec.api-key.store=redis"),
            "cosec-cocache",
        )
    }

    @Test
    fun `API keys in memory, or turned off, need no cosec-cocache`() {
        val noCache = plain.withClassLoader(FilteredClassLoader("me.ahoo.cosec.cocache.RedisApiKeyStore"))
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

    private fun failsNamingTheStarter(runner: ApplicationContextRunner) {
        runner.run { context ->
            val failure = context.startupFailure
            failure.assert().isNotNull()
            generateSequence(
                failure
            ) { it.cause }.any { "me.ahoo.cocache:cocache-spring-boot-starter" in it.message.orEmpty() }
                .assert().describedAs(failure.toString()).isTrue()
        }
    }

    @Test
    fun `cosec-cocache without a CacheRuntime fails startup naming the CoCache starter`() {
        failsNamingTheStarter(
            ApplicationContextRunner().withConfiguration(
                config
            ).withBean(ReactiveStringRedisTemplate::class.java, redis)
        )
    }

    /** With JWT on, the token store that needs a cache would otherwise fail first, asking for the module already there. */
    @Test
    fun `the check comes before the beans that need a CacheRuntime`() {
        failsNamingTheStarter(
            ApplicationContextRunner()
                .withConfiguration(
                    AutoConfigurations.of(
                        CoSecModuleCheckAutoConfiguration::class.java,
                        CoSecTokenRevocationCacheAutoConfiguration::class.java,
                        CoSecJwtAutoConfiguration::class.java,
                    )
                )
                .withPropertyValues("cosec.jwt.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_")
                .withBean(ReactiveStringRedisTemplate::class.java, redis)
        )
    }

    @Test
    fun `with CoSec off, or without cosec-cocache, no CacheRuntime is needed`() {
        val noRuntime = ApplicationContextRunner().withConfiguration(
            config
        ).withBean(ReactiveStringRedisTemplate::class.java, redis)
        starts(noRuntime.withPropertyValues("cosec.enabled=false"))
        starts(
            noRuntime.withClassLoader(FilteredClassLoader("me.ahoo.cosec.cocache.snapshot.RedisSnapshotDocumentStore"))
        )
    }

    /**
     * #1223: a service behind a gateway (`cosec.pipeline.mode=gateway`) wires no snapshots, memberships, API keys or
     * control plane, so a `cosec-cocache` still on its classpath is not used and needs no Redis.
     */
    @Test
    fun `a service in gateway mode does not need Redis for an unused cosec-cocache`() {
        // No Redis template and no CacheRuntime: nothing here uses either.
        val gateway = ApplicationContextRunner().withConfiguration(config)
            .withPropertyValues("cosec.pipeline.mode=gateway", "cosec.jwt.enabled=false")
        starts(gateway)
    }

    /** STA-1 still holds where the module is used: the data plane's modes, and JWT, which keeps its revocations in Redis. */
    @Test
    fun `cosec-cocache is still needed where it is used`() {
        val noRedis = ApplicationContextRunner().withConfiguration(config).withBean(CacheRuntime::class.java, runtime)
        fun failsNamingTemplate(runner: AbstractApplicationContextRunner<*, *, *>) = runner.run { context ->
            val failure = context.startupFailure
            failure.assert().isNotNull()
            generateSequence(failure) { it.cause }.any { "ReactiveStringRedisTemplate" in it.message.orEmpty() }
                .assert().describedAs(failure.toString()).isTrue()
        }
        // The default mode decides with snapshots, whether or not JWT is on.
        failsNamingTemplate(noRedis)
        failsNamingTemplate(noRedis.withPropertyValues("cosec.pipeline.mode=enforce", "cosec.jwt.enabled=false"))
        // A gateway-mode service that turns JWT on issues tokens, and logout revokes them in Redis.
        failsNamingTemplate(noRedis.withPropertyValues("cosec.pipeline.mode=gateway", "cosec.jwt.enabled=true"))
        starts(
            noRedis.withPropertyValues("cosec.pipeline.mode=gateway", "cosec.jwt.enabled=true")
                .withBean(ReactiveStringRedisTemplate::class.java, redis)
        )
    }

    @Test
    fun `without cosec-cocache, or with CoSec off, no Redis template is needed`() {
        val noRedis = ApplicationContextRunner().withConfiguration(config)
        starts(
            noRedis.withClassLoader(FilteredClassLoader("me.ahoo.cosec.cocache.snapshot.RedisSnapshotDocumentStore"))
        )
        starts(noRedis.withPropertyValues("cosec.enabled=false"))
        starts(plain)
    }

    // STA-3: each check above fails startup under lazy initialization too, where no bean asks for it.

    @Test
    fun `a Spring MVC host without cosec-webmvc fails startup lazily too`() {
        failsNaming(
            servlet.withClassLoader(FilteredClassLoader("me.ahoo.cosec.webmvc.PipelineServletFilter")).lazily(),
            "cosec-webmvc"
        )
    }

    @Test
    fun `a WebFlux host without cosec-webflux fails startup lazily too`() {
        failsNaming(
            reactive.withClassLoader(
                FilteredClassLoader(GATEWAY_GLOBAL_FILTER, "me.ahoo.cosec.webflux.PipelineWebFilter")
            ).lazily(),
            "cosec-webflux",
        )
    }

    @Test
    fun `a Spring Cloud Gateway host without cosec-gateway fails startup lazily too`() {
        failsNaming(
            reactive.withClassLoader(FilteredClassLoader("me.ahoo.cosec.gateway.IdentityStripFilter")).lazily(),
            "cosec-gateway"
        )
    }

    @Test
    fun `Redis API keys without cosec-cocache fail startup lazily too`() {
        failsNaming(
            plain.withClassLoader(FilteredClassLoader("me.ahoo.cosec.cocache.RedisApiKeyStore"))
                .withPropertyValues("cosec.api-key.enabled=true", "cosec.api-key.store=redis").lazily(),
            "cosec-cocache",
        )
    }

    @Test
    fun `a CosId generator for CoSec's ids without cosec-cosid fails startup lazily too`() {
        failsNaming(
            plain.withClassLoader(FilteredClassLoader("me.ahoo.cosec.cosid.CosIdIdGenerator"))
                .withPropertyValues("cosec.cosid.generator=order").lazily(),
            "cosec-cosid",
        )
    }

    @Test
    fun `a required Kafka audit without cosec-kafka fails startup lazily too`() {
        failsNaming(
            plain.withClassLoader(FilteredClassLoader("me.ahoo.cosec.kafka.KafkaAuditListener"))
                .withPropertyValues("cosec.audit.kafka.delivery=required").lazily(),
            "cosec-kafka",
        )
    }

    @Test
    fun `cosec-cocache without a Redis template fails startup lazily too`() {
        ApplicationContextRunner().withConfiguration(config).lazily().run { context ->
            generateSequence(
                context.startupFailure
            ) { it.cause }.any { "spring-boot-data-redis" in it.message.orEmpty() }
                .assert().describedAs(context.startupFailure.toString()).isTrue()
        }
    }

    @Test
    fun `cosec-cocache without a CacheRuntime fails startup lazily too`() {
        failsNamingTheStarter(
            ApplicationContextRunner().withConfiguration(
                config
            ).withBean(ReactiveStringRedisTemplate::class.java, redis).lazily()
        )
    }
}
