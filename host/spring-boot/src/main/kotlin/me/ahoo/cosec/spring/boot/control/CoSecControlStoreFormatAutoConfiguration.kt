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

package me.ahoo.cosec.spring.boot.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.control.CoCacheControlStore
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.StartupTaskExclusion
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import java.time.Duration

/**
 * Where the control plane runs over CoCache: one ERROR at startup while its store is in the format before
 * 8.0.0-beta.5, naming `cosec-migrate repair-references`, and the `cosecControlStoreFormat` health detail. It never
 * rewrites the store.
 */
@AutoConfiguration(after = [CoSecControlAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@ConditionalOnBean(ControlStore::class)
@Import(StartupTaskExclusion::class)
internal class CoSecControlStoreFormatAutoConfiguration {
    /** Reads the old document list every [INTERVAL]; a store that is not CoCache's has no old format. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun cosecControlStoreFormatMonitor(store: ControlStore): ControlStoreFormatMonitor = ControlStoreFormatMonitor(
        { (store as? CoCacheControlStore)?.let { runBlocking(Dispatchers.Default) { it.legacyDocuments() } } != null },
        INTERVAL,
    ).start()

    /** `cosecControlStoreFormat` in the health endpoint: an alert, not a readiness signal. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["org.springframework.boot.health.contributor.HealthIndicator"])
    @InternalCoSecApi
    class FormatHealth {
        @Bean
        @ConditionalOnMissingBean(name = ["cosecControlStoreFormatHealthIndicator"])
        fun cosecControlStoreFormatHealthIndicator(
            monitor: ControlStoreFormatMonitor,
        ): ControlStoreFormatHealthIndicator = ControlStoreFormatHealthIndicator(monitor)
    }

    private companion object {
        val INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
