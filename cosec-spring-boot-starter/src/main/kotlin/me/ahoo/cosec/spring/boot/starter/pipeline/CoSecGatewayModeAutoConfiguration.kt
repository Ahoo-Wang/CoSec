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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosid.StringIdGenerator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

private val log = KotlinLogging.logger {}

/**
 * A service behind a gateway (`cosec.pipeline.mode=gateway`): the web adapters read who the caller is from the
 * `CoSec-Identity` header the gateway forwarded ([IdentityGate]), and nothing else is wired, no authenticator, keys,
 * snapshot, policies, control plane or Redis. It trusts the network boundary: only the gateway must be able to reach
 * this service, since the gateway is what strips any identity a client sends.
 */
@AutoConfiguration
@ConditionalOnCoSecEnabled
@ConditionalOnProperty(value = [PipelineProperties.MODE_KEY], havingValue = "gateway")
@EnableConfigurationProperties(
    PipelineProperties::class,
    RevocationProperties::class,
    // The web adapters read the host app and the trusted proxy hops from it.
    RuntimeAuthorizationProperties::class,
)
class CoSecGatewayModeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun cosecIdentityGate(
        pipeline: PipelineProperties,
        revocation: RevocationProperties,
        idGenerator: ObjectProvider<StringIdGenerator>,
    ): IdentityGate {
        log.warn {
            "cosec.pipeline.mode=gateway: this service believes any CoSec-Identity header it receives. Only the CoSec " +
                "gateway, which strips the clients' own, may be able to reach it: anyone else who can is whoever they claim."
        }
        return IdentityGate(
            maxBytes = pipeline.gateway.maxSize.toBytes().toInt(),
            maxAge = pipeline.gateway.maxAge,
            skew = revocation.skew,
            idGenerator = idGenerator.ifAvailable,
        )
    }
}
