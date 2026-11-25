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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.http.HttpControlService
import me.ahoo.cosec.control.sdk.ControlService
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.StartupTask
import me.ahoo.cosec.spring.boot.StartupTaskExclusion
import me.ahoo.cosec.spring.boot.pipeline.IdentityHeaderLimit
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import java.net.http.HttpClient

/**
 * The control plane called on a control-plane host (`CTL-77`, `STA-11`): where `cosec.control.service-url` names one and
 * `cosec-control-http` is on the classpath, the application's `ControlService` is [HttpControlService], the same calls
 * and answers as the in-process implementation's. An instance that runs the control plane never gets here: naming a host
 * there fails startup ([ControlServiceChoice]).
 */
@AutoConfiguration(after = [CoSecControlAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@ConditionalOnClass(name = [ControlServiceChoice.HTTP_SERVICE])
@ConditionalOnProperty(ControlServiceChoice.KEY)
@EnableConfigurationProperties(ControlProperties::class)
@Import(StartupTaskExclusion::class)
internal class CoSecControlClientAutoConfiguration {
    /** Replaceable, by a client of the application's own (its `HttpClient`, its TLS). */
    @Bean
    @ConditionalOnMissingBean
    fun cosecControlService(properties: ControlProperties): ControlService =
        HttpControlService(checkNotNull(properties.serviceUrl), HttpClient.newHttpClient(), app = properties.appId)

    /** `IDN-43`: a host that reads the identity relays within what it reads, or does not start. */
    @Bean
    fun cosecRelayLimitCheck(environment: Environment): StartupTask =
        StartupTask.OnCreation { IdentityHeaderLimit.requireRelayWithinReads(environment) }
}
