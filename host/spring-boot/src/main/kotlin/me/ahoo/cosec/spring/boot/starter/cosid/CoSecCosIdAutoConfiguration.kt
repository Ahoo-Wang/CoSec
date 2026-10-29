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

package me.ahoo.cosec.spring.boot.starter.cosid

import me.ahoo.cosec.cosid.CosIdIdGenerator
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosid.runtime.CosIdRuntime
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/** CosId's own auto-configuration, which starts the runtime. */
private const val COSID_AUTO_CONFIGURATION = "me.ahoo.cosid.spring.autoconfigure.CosIdAutoConfiguration"

/**
 * CoSec's [IdGenerator] from the application's CosId runtime (`cosec-cosid`): request, decision event, token and
 * change ids from the generator `cosec.cosid.generator` names, CosId's `default` unless set. Only when the application
 * has a [CosIdRuntime] and no `IdGenerator` of its own; otherwise the starter's beans use CoSec's
 * [me.ahoo.cosec.spi.id.RandomIdGenerator]. The OAuth `state` of social login and API key secrets never come from it.
 */
@AutoConfiguration(afterName = [COSID_AUTO_CONFIGURATION])
@ConditionalOnCoSecEnabled
@ConditionalOnClass(CosIdIdGenerator::class)
@ConditionalOnBean(CosIdRuntime::class)
@EnableConfigurationProperties(CosIdGeneratorProperties::class)
class CoSecCosIdAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(IdGenerator::class)
    fun cosecIdGenerator(runtime: CosIdRuntime, properties: CosIdGeneratorProperties): IdGenerator =
        CosIdIdGenerator.of(runtime, properties.generator)
}
