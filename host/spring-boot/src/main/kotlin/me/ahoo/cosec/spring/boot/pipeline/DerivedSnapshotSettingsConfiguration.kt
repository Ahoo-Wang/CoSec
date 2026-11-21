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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.StartupTask
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

private val log = KotlinLogging.logger {}

/** One line for what the data plane computes where it used to be told, and from what (`SNP-23`). */
@Configuration(proxyBeanMethods = false)
internal class DerivedSnapshotSettingsConfiguration {
    @Bean
    fun cosecDerivedSnapshotSettingsNotice(
        properties: RuntimeAuthorizationProperties,
        pipeline: PipelineProperties,
        environment: Environment,
    ): StartupTask = StartupTask.AfterSingletons {
        log.info { DerivedSnapshotSettings.line(properties, pipeline.timeout, environment) }
    }
}
