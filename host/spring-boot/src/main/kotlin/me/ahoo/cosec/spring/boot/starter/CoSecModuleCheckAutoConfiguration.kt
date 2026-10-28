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

package me.ahoo.cosec.spring.boot.starter

import me.ahoo.cosec.spring.boot.starter.apikey.ApiKeyProperties
import me.ahoo.cosec.spring.boot.starter.audit.kafka.KafkaAuditProperties
import me.ahoo.cosec.spring.boot.starter.pipeline.GATEWAY_GLOBAL_FILTER
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.ClassUtils

/**
 * `STA-1` (RFC 0011 D2): the starter wires each module it finds on the classpath, so a module the application forgot
 * would silently turn a protection off: no filter on a web host, a gateway that passes a client's `CoSec-Identity` on,
 * API keys with no store, a required audit with no listener. Each check here runs under the conditions of the feature
 * that needs the module, and fails startup naming the artifact to add. A module no feature needs may be missing.
 */
@AutoConfiguration
class CoSecModuleCheckAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnCoSecEnabled
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    class WebMvc {
        @Bean
        fun cosecWebMvcModuleCheck(context: ApplicationContext): InitializingBean =
            requireModule(
                context,
                "me.ahoo.cosec.servlet.PipelineServletFilter",
                "cosec-webmvc",
                "A Spring MVC host",
            )
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnCoSecEnabled
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnMissingClass(GATEWAY_GLOBAL_FILTER)
    class WebFlux {
        @Bean
        fun cosecWebFluxModuleCheck(context: ApplicationContext): InitializingBean =
            requireModule(
                context,
                "me.ahoo.cosec.webflux.PipelineWebFilter",
                "cosec-webflux",
                "A WebFlux host",
            )
    }

    /** Whether or not CoSec is enabled: a gateway strips the client's identity header always (PIP-10). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = [GATEWAY_GLOBAL_FILTER])
    class Gateway {
        @Bean
        fun cosecGatewayModuleCheck(context: ApplicationContext): InitializingBean =
            requireModule(
                context,
                "me.ahoo.cosec.gateway.IdentityStripFilter",
                "cosec-gateway",
                "A Spring Cloud Gateway host",
            )
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(value = [ApiKeyProperties.ENABLED_KEY], havingValue = "true")
    @ConditionalOnProperty(value = [ApiKeyProperties.STORE_KEY], havingValue = "redis")
    class RedisApiKeys {
        @Bean
        fun cosecRedisApiKeyModuleCheck(context: ApplicationContext): InitializingBean =
            requireModule(
                context,
                "me.ahoo.cosec.cache.RedisApiKeyStore",
                "cosec-cocache",
                "${ApiKeyProperties.STORE_KEY}=redis",
            )
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(value = [KafkaAuditProperties.PREFIX + ".delivery"], havingValue = "required")
    class RequiredKafkaAudit {
        @Bean
        fun cosecKafkaAuditModuleCheck(context: ApplicationContext): InitializingBean =
            requireModule(
                context,
                "me.ahoo.cosec.kafka.KafkaAuditListener",
                "cosec-kafka",
                "${KafkaAuditProperties.PREFIX}.delivery=required",
            )
    }

    companion object {
        fun requireModule(
            context: ApplicationContext,
            className: String,
            artifact: String,
            what: String,
        ): InitializingBean =
            InitializingBean {
                check(ClassUtils.isPresent(className, context.classLoader)) {
                    "$what needs $artifact (me.ahoo.cosec:$artifact) on the classpath: add it to the application's " +
                        "dependencies (STA-1)."
                }
            }
    }
}
