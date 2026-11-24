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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.shadow.CoCacheShadowReports
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spring.boot.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.JwtProperties
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import java.util.Collections
import java.util.UUID

/**
 * `SNP-25` (RFC 0014 R5): every process that reads the store through `cosec-cocache` reports its status, whatever its
 * pipeline mode and whether it shadows; it reports before the snapshot source reads a document; with shadowing on, one
 * instance id serves the status and the shadow reports; without `cosec-cocache` nothing reports.
 */
class InstanceStatusWiringTest {
    /** The singletons in the order they were created. */
    private val created: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val runner = ReactiveWebApplicationContextRunner()
        .withClassLoader(FilteredClassLoader("org.springframework.cloud.gateway.filter.GlobalFilter"))
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                DataRedisAutoConfiguration::class.java,
                DataRedisReactiveAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecInstanceStatusAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            "cosec.key-prefix=status-wiring-${UUID.randomUUID()}",
            "cosec.authorization.routes.location=",
            "cocache.backend=memory",
        )
        .withBean(CoherenceBackend::class.java, { InMemoryBackend() })
        .withBean("creationOrder", BeanPostProcessor::class.java, {
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                    created += beanName
                    return bean
                }
            }
        })

    private fun statuses(reports: CoCacheShadowReports): List<InstanceStatus> = runBlocking { reports.statuses() }

    @Test
    fun `an instance that does not shadow reports, before its snapshot source reads a document`() {
        runner.withPropertyValues("cosec.authorization.shadow.enabled=false").run { context ->
            context.startupFailure.assert().isNull()
            val reporting = context.getBean(InstanceReporting::class.java)
            context.containsBean("cosecShadowReporting").assert().isFalse()
            val status = statuses(context.getBean(CoCacheShadowReports::class.java)).single()
            status.instance.assert().isEqualTo(reporting.reporter.instance)
            status.shadows.assert().isFalse()
            status.release.assert().isEqualTo(InstanceStatus.RELEASE)
            created.indexOf("cosecInstanceReporting").assert()
                .isLessThan(created.indexOf("cosecSnapshotSource"))
                .isGreaterThanOrEqualTo(0)
        }
    }

    @Test
    fun `a service behind a gateway reports, though it reads no document`() {
        runner.withPropertyValues(
            "cosec.pipeline.mode=gateway",
            "cosec.pipeline.identity.only-gateway-can-reach=true",
        ).run { context ->
            context.startupFailure.assert().isNull()
            context.containsBean("cosecSnapshotSource").assert().isFalse()
            val reporting = context.getBean(InstanceReporting::class.java)
            statuses(context.getBean(CoCacheShadowReports::class.java)).single().instance.assert()
                .isEqualTo(reporting.reporter.instance)
        }
    }

    @Test
    fun `with shadowing on, the status and the shadow reports are one instance's`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            val reporting = context.getBean(InstanceReporting::class.java)
            context.getBean(ShadowRecorder::class.java).instance.assert().isEqualTo(reporting.reporter.instance)
            context.getBean(ShadowReporting::class.java).reporter.instanceReporter.assert().isSameAs(reporting.reporter)
            reporting.reporter.drivenByShadows.assert().isTrue()
        }
    }

    @Test
    fun `its health names the instance and the release, and stays up`() {
        runner.withPropertyValues("cosec.authorization.shadow.enabled=false").run { context ->
            val health = context.getBean(InstanceStatusHealthIndicator::class.java).health()
            health.status.assert().isEqualTo(Status.UP)
            health.details["instance"].assert().isEqualTo(context.getBean(InstanceReporting::class.java).reporter.instance)
            health.details["release"].assert().isEqualTo(InstanceStatus.RELEASE)
            health.details.assert().containsKey("lastWritten")
        }
    }

    @Test
    fun `without a shared store nothing reports`() {
        ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoSecInstanceStatusAutoConfiguration::class.java))
            .run { context ->
                context.startupFailure.assert().isNull()
                context.getBeanNamesForType(InstanceReporting::class.java).assert().isEmpty()
            }
    }
}
