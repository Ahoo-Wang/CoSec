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

package me.ahoo.cosec.spring.boot

import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Fails startup on a `cosec.*` property CoSec does not read ([UnknownProperties]), whatever else is configured, and
 * before any bean is created: a configuration with removed keys hears of every one of them first, before the store or
 * module checks, and an application bean that needs CoSec's beans (created before any auto-configuration's) cannot make
 * another failure speak first.
 */
@AutoConfiguration
@Import(StartupTaskExclusion::class)
// A class, not an object: Spring instantiates an auto-configuration.
@Suppress("UtilityClassWithPublicConstructor")
internal class CoSecPropertyCheckAutoConfiguration {
    /** UB-5: what CoSec is configured as, once every singleton exists; one WARN when `cosec.enabled=false`. */
    @Bean
    fun cosecStartupSummary(context: ApplicationContext): StartupTask = StartupTask.AfterSingletons {
        StartupSummary.log(StartupSummary.report(context, context.environment))
    }

    companion object {
        /** Static, so Spring runs it with the post-processors, before it instantiates any configuration or bean. */
        @JvmStatic
        @Bean
        fun cosecUnknownPropertiesCheck(environment: ConfigurableEnvironment): StartupTask.BeforeBeans =
            StartupTask.BeforeBeans {
                UnknownProperties.load(CoSecPropertyCheckAutoConfiguration::class.java.classLoader).check(environment)
                CoSecSwitches.check(environment)
                RetiredCacheSettings.check(environment)
            }
    }
}
