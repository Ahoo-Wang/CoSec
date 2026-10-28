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

package me.ahoo.cosec.spring.boot.starter.pipeline

import me.ahoo.cosec.gateway.IdentityHeadersFilter
import me.ahoo.cosec.gateway.IdentityStripFilter
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.ClassUtils

/**
 * What holds whatever else is configured, `cosec.enabled=false` included:
 *
 * - `cosec.pipeline.mode` is bound and checked on every host, so a value that is neither `enforce` nor `gateway` fails
 *   startup instead of wiring no pipeline at all; and `gateway` on a Spring Cloud Gateway host fails startup: a gateway
 *   always decides with policies, and in `gateway` mode it would forward every request unchecked.
 * - On a gateway host, the client's `CoSec-Identity` is stripped from every request ([IdentityStripFilter]) and the
 *   routing filters send only what the pipeline forwarded ([IdentityHeadersFilter]): stripping a reserved header is
 *   never wrong, and without these a gateway with the pipeline off would pass a client's identity to every backend.
 */
@AutoConfiguration
@EnableConfigurationProperties(PipelineProperties::class)
class CoSecIdentityGuardAutoConfiguration {
    @Bean
    fun cosecPipelineModeCheck(properties: PipelineProperties): InitializingBean = InitializingBean {
        check(
            properties.mode != PipelineProperties.Mode.GATEWAY || !ClassUtils.isPresent(GATEWAY_GLOBAL_FILTER, null)
        ) {
            "${PipelineProperties.MODE_KEY}=gateway on a Spring Cloud Gateway host: a gateway decides with policies " +
                "(enforce), and strips and forwards the identity the services behind it read in gateway mode."
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.gateway.IdentityStripFilter", GATEWAY_GLOBAL_FILTER])
    class Gateway {
        @Bean
        @ConditionalOnMissingBean
        fun cosecIdentityStripFilter(): IdentityStripFilter = IdentityStripFilter()

        @Bean
        @ConditionalOnMissingBean
        fun cosecIdentityHeadersFilter(): IdentityHeadersFilter = IdentityHeadersFilter()
    }
}
