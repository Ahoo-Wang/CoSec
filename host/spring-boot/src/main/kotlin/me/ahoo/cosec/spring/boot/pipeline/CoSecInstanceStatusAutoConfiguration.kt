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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.shadow.CoCacheShadowReports
import me.ahoo.cosec.runtime.shadow.InstanceReporter
import me.ahoo.cosec.spring.boot.CoSecProperties
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.StartupTask
import me.ahoo.cosec.spring.boot.StartupTaskExclusion
import me.ahoo.cosec.spring.boot.authorization.cache.COCACHE_AUTO_CONFIGURATION
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Every CoSec process that reads the shared store through `cosec-cocache` reports its status (RFC 0014 R5, `SNP-25`):
 * whatever its pipeline mode, whether it shadows or not. A data-plane instance, a gateway, a control-plane host and an
 * IAM service behind a gateway each write `<s>:shadow:status:<instance>` with the release they run, so the control plane
 * sees every reader of the store. With shadowing on, the shadow reporter writes it once a cycle, after its reports; without,
 * [InstanceReporting] does. Before any document is read: the snapshot source waits for the first report.
 */
@AutoConfiguration(before = [CoSecSnapshotAutoConfiguration::class], afterName = [COCACHE_AUTO_CONFIGURATION])
@ConditionalOnCoSecEnabled
@ConditionalOnClass(name = ["me.ahoo.cosec.cocache.shadow.CoCacheShadowReports"])
@ConditionalOnBean(CoCacheFactory::class)
@EnableConfigurationProperties(RuntimeAuthorizationProperties::class, CoSecProperties::class)
@Import(StartupTaskExclusion::class)
@InternalCoSecApi
class CoSecInstanceStatusAutoConfiguration {
    /** Shared with the control plane on a host that runs it, which reads the statuses and reports this writes. */
    @Bean
    @ConditionalOnMissingBean
    fun cosecShadowReports(factory: CoCacheFactory, cosec: CoSecProperties): CoCacheShadowReports =
        CoCacheShadowReports.create(factory, cosec.keyPrefix)

    /** This instance's id for this start, which the shadow recorder takes too: its status, resident and report keys are one instance's. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun cosecInstanceReporting(
        reports: CoCacheShadowReports,
        properties: RuntimeAuthorizationProperties,
        cosec: CoSecProperties,
    ): InstanceReporting {
        val instance = ShadowInstanceId.forThisStart(properties.shadow.instanceId)
        val prefix = cosec.keyPrefix
        val keys = "~$prefix:shadow:status:$instance, ~$prefix:shadow:resident:$instance and ~$prefix:shadow:instances:*"
        return InstanceReporting(InstanceReporter(instance, reports, keys = keys))
    }

    /** Status writes that failed (`cosec.instance.status.failed`): an instance the control plane cannot see; alert on any. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry::class)
    @InternalCoSecApi
    class Meters {
        @Bean
        fun cosecInstanceStatusMeter(
            registry: ObjectProvider<MeterRegistry>,
            reporting: ObjectProvider<InstanceReporting>,
        ): StartupTask = StartupTask.AfterSingletons {
            val meters = registry.ifAvailable ?: return@AfterSingletons
            reporting.ifAvailable?.let { bound ->
                FunctionCounter.builder(FAILED_WRITES, bound) { it.reporter.failedWrites.get().toDouble() }
                    .description("Writes of this instance's status that failed: the control plane cannot see it (SNP-25)")
                    .register(meters)
            }
        }
    }

    /** `cosecInstanceStatus`: the status this instance reports. Always UP, in no readiness group. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["org.springframework.boot.health.contributor.HealthIndicator"])
    @InternalCoSecApi
    class Health {
        @Bean
        @ConditionalOnMissingBean(name = ["cosecInstanceStatusHealthIndicator"])
        fun cosecInstanceStatusHealthIndicator(reporting: InstanceReporting): InstanceStatusHealthIndicator =
            InstanceStatusHealthIndicator(reporting.reporter)
    }

    @InternalCoSecApi
    companion object {
        const val FAILED_WRITES = "cosec.instance.status.failed"
    }
}
