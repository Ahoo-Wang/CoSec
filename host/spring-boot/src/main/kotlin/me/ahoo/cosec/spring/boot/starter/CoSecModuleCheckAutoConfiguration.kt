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

package me.ahoo.cosec.spring.boot.starter

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spring.boot.starter.apikey.ApiKeyProperties
import me.ahoo.cosec.spring.boot.starter.audit.kafka.KafkaAuditProperties
import me.ahoo.cosec.spring.boot.starter.audit.kafka.kafkaAuditDelivery
import me.ahoo.cosec.spring.boot.starter.cosid.CosIdGeneratorProperties
import me.ahoo.cosec.spring.boot.starter.jwt.ConditionalOnJwtEnabled
import me.ahoo.cosec.spring.boot.starter.pipeline.ConditionalOnEnforceMode
import me.ahoo.cosec.spring.boot.starter.pipeline.GATEWAY_GLOBAL_FILTER
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureOrder
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ConfigurationCondition
import org.springframework.context.annotation.Import
import org.springframework.core.Ordered
import org.springframework.util.ClassUtils

/**
 * `STA-1` (RFC 0011 D2): the starter wires each module it finds on the classpath, so a module the application forgot
 * would silently turn a protection off: no filter on a web host, a gateway that passes a client's `CoSec-Identity` on,
 * API keys with no store, a required audit with no listener. Each check here runs under the conditions of the feature
 * that needs the module, and fails startup naming the artifact to add. A module no feature needs may be missing.
 *
 * First of the auto-configurations, so that the bean a missing module would have made does not fail first with a
 * message about something else.
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@Import(StartupCheckExclusion::class)
internal class CoSecModuleCheckAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnCoSecEnabled
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    class WebMvc {
        @Bean
        fun cosecWebMvcModuleCheck(context: ApplicationContext): StartupCheck =
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
        fun cosecWebFluxModuleCheck(context: ApplicationContext): StartupCheck =
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
        fun cosecGatewayModuleCheck(context: ApplicationContext): StartupCheck =
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
        fun cosecRedisApiKeyModuleCheck(context: ApplicationContext): StartupCheck =
            requireModule(
                context,
                "me.ahoo.cosec.cache.RedisApiKeyStore",
                "cosec-cocache",
                "${ApiKeyProperties.STORE_KEY}=redis",
            )
    }

    /** Read with the listener's own parse ([kafkaAuditDelivery]), so the check and the listener never disagree. */
    @Configuration(proxyBeanMethods = false)
    class RequiredKafkaAudit {
        @Bean
        fun cosecKafkaAuditModuleCheck(context: ApplicationContext): StartupCheck = StartupCheck.OnCreation {
            if (kafkaAuditDelivery(context.environment) == Delivery.REQUIRED) {
                requireModule(
                    context,
                    "me.ahoo.cosec.kafka.KafkaAuditListener",
                    "cosec-kafka",
                    "${KafkaAuditProperties.DELIVERY_KEY}=required",
                ).afterPropertiesSet()
            }
        }
    }

    /**
     * A CosId generator named for CoSec's ids, which would otherwise be CoSec's own without a word: the module must be
     * there, and CoSec's ids must then come from it. A property that is set and does nothing fails startup, as an unknown
     * `cosec.*` property does: without a `CosIdRuntime`, or beside an `IdGenerator` bean of the application's, the
     * generator it names would never be read (round 2 L7).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnCoSecEnabled
    @ConditionalOnProperty(value = [CosIdGeneratorProperties.GENERATOR_KEY])
    class CosIdGenerator {
        @Bean
        fun cosecCosIdModuleCheck(context: ApplicationContext): StartupCheck {
            val module = requireModule(
                context,
                "me.ahoo.cosec.cosid.CosIdIdGenerator",
                "cosec-cosid",
                CosIdGeneratorProperties.GENERATOR_KEY,
            )
            return StartupCheck.OnCreation {
                module.afterPropertiesSet()
                check(context.containsBean(CosIdGeneratorProperties.ID_GENERATOR_BEAN)) {
                    "${CosIdGeneratorProperties.GENERATOR_KEY} is set, but CoSec's ids do not come from CosId: there is no " +
                        "CosIdRuntime bean (configure CosId, me.ahoo.cosid:cosid-spring-boot-starter), or an IdGenerator " +
                        "bean of the application's own gives them. Configure CosId, or remove the property (STA-1)."
                }
            }
        }
    }

    /**
     * `cosec-cocache` keeps snapshots, revocations, rate limits and API keys in Redis: without a Redis connection the
     * rate limiter would fall back to one count per instance, and no snapshot could be read. Its caches are made by
     * CoCache's starter: without a `CacheRuntime`, snapshots would fall back to an empty store that never gets ready.
     *
     * Only where the module is used ([OnCoCacheUsed]): a service behind a gateway (`cosec.pipeline.mode=gateway`) with
     * JWT off wires none of it, and a `cosec-cocache` its dependencies happen to bring needs no Redis (#1223).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnCoSecEnabled
    @ConditionalOnClass(name = [COCACHE_MARKER])
    @Conditional(OnCoCacheUsed::class)
    class CoCacheRedis {
        @Bean
        fun cosecCoCacheRedisCheck(context: ApplicationContext): StartupCheck = StartupCheck.OnCreation {
            val present = ClassUtils.isPresent(REACTIVE_REDIS_TEMPLATE, context.classLoader) &&
                context.getBeanNamesForType(ClassUtils.forName(REACTIVE_REDIS_TEMPLATE, context.classLoader)).isNotEmpty()
            check(present) {
                "cosec-cocache needs a ReactiveStringRedisTemplate bean: add org.springframework.boot:" +
                    "spring-boot-data-redis to the application's dependencies and configure Redis (STA-1)."
            }
        }

        @Bean
        fun cosecCoCacheStarterCheck(context: ApplicationContext): StartupCheck = StartupCheck.OnCreation {
            val present = ClassUtils.isPresent(CACHE_RUNTIME, context.classLoader) &&
                context.getBeanNamesForType(ClassUtils.forName(CACHE_RUNTIME, context.classLoader)).isNotEmpty()
            check(present) {
                "cosec-cocache needs a CacheRuntime bean: add me.ahoo.cocache:cocache-spring-boot-starter to the " +
                    "application's dependencies (STA-1)."
            }
        }
    }

    companion object {
        private const val COCACHE_MARKER = "me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore"
        private const val REACTIVE_REDIS_TEMPLATE = "org.springframework.data.redis.core.ReactiveStringRedisTemplate"
        private const val CACHE_RUNTIME = "me.ahoo.cocache.core.CacheRuntime"

        fun requireModule(
            context: ApplicationContext,
            className: String,
            artifact: String,
            what: String,
        ): StartupCheck.OnCreation =
            StartupCheck.OnCreation {
                check(ClassUtils.isPresent(className, context.classLoader)) {
                    "$what needs $artifact (me.ahoo.cosec:$artifact) on the classpath: add it to the application's " +
                        "dependencies (STA-1)."
                }
            }
    }
}

/**
 * Whether the host uses `cosec-cocache`: it decides with policies (`enforce`, the default mode), which reads the
 * snapshots, memberships and API keys from Redis, or JWT is on, which keeps its revocations there, as on a service
 * behind a gateway that issues tokens. Anything else, a gateway-mode service that issues none, leaves it unused.
 */
internal class OnCoCacheUsed : AnyNestedCondition(ConfigurationCondition.ConfigurationPhase.PARSE_CONFIGURATION) {
    @ConditionalOnEnforceMode
    class Enforcing

    @ConditionalOnJwtEnabled
    class Jwt
}
