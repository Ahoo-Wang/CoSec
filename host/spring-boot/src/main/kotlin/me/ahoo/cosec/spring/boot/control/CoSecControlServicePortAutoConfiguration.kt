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

package me.ahoo.cosec.spring.boot.control

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.lifecycle.ControlDerived
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.StartupTask
import me.ahoo.cosec.spring.boot.StartupTaskExclusion
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment

private val log = KotlinLogging.logger {}

/**
 * The service port's refusals (`CTL-58`, [ControlServicePort]), on every host that runs the control plane, whether or
 * not it serves the control API's routes: a port with the API off, or beside the gateway's statement, is a configuration
 * mistake either way.
 */
@AutoConfiguration(after = [CoSecControlAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@Conditional(ControlPlaneCondition::class)
@Import(StartupTaskExclusion::class)
internal class CoSecControlServicePortAutoConfiguration {
    /** Once every singleton exists: a service port with no WebFlux server to serve it. */
    @Bean
    fun cosecControlServicePortCheck(environment: Environment, beans: ListableBeanFactory): StartupTask =
        StartupTask.AfterSingletons { ControlServicePort.requireListener(environment, beans) }

    /** One line for what the control plane computes where it used to be told, and from what (the control group). */
    @Bean
    fun cosecDerivedControlSettingsNotice(properties: ControlProperties): StartupTask = StartupTask.AfterSingletons {
        log.info { "CoSec derived, not configured: ${ControlDerived.describe(properties.shadow.minDuration)}." }
    }

    companion object {
        /** Static, so Spring runs it with the post-processors, before any bean: what the environment alone refuses. */
        @JvmStatic
        @Bean
        fun cosecControlServicePortEnvironmentCheck(environment: Environment): StartupTask.BeforeBeans =
            StartupTask.BeforeBeans { ControlServicePort.check(environment) }
    }
}
