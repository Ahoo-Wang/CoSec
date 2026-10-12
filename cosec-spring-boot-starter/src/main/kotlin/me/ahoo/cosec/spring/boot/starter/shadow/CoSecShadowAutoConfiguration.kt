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
package me.ahoo.cosec.spring.boot.starter.shadow

import me.ahoo.cosec.shadow.LocalShadowPolicyProvider
import me.ahoo.cosec.shadow.LoggingShadowEventSink
import me.ahoo.cosec.shadow.ShadowEventSink
import me.ahoo.cosec.shadow.ShadowPolicyProvider
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecAuthorizationAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * CoSec Shadow AutoConfiguration, active only with `cosec.shadow.enabled=true`.
 *
 * Provides the candidate policies and the diff sink; [CoSecAuthorizationAutoConfiguration] wraps the authorization
 * with `ShadowAuthorization` when they are present.
 */
@AutoConfiguration(before = [CoSecAuthorizationAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@ConditionalOnProperty(value = [ShadowProperties.ENABLED_KEY], havingValue = "true")
@EnableConfigurationProperties(ShadowProperties::class)
class CoSecShadowAutoConfiguration(private val shadowProperties: ShadowProperties) {

    @Bean
    @ConditionalOnMissingBean
    fun shadowPolicyProvider(): ShadowPolicyProvider {
        return LocalShadowPolicyProvider(shadowProperties.locations)
    }

    @Bean
    fun shadowPolicyLoaderLifecycle(shadowPolicyProvider: ShadowPolicyProvider): ShadowPolicyLoaderLifecycle {
        return ShadowPolicyLoaderLifecycle(shadowPolicyProvider)
    }

    @Bean
    @ConditionalOnMissingBean
    fun shadowEventSink(): ShadowEventSink {
        return LoggingShadowEventSink()
    }
}
