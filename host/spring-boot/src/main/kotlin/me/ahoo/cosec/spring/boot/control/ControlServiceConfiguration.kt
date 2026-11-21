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
import me.ahoo.cosec.control.api.ControlPlane
import me.ahoo.cosec.control.sdk.ControlService
import me.ahoo.cosec.control.local.LocalControlService
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.cosec.spring.boot.pipeline.PipelineProperties
import me.ahoo.cosec.spring.boot.pipeline.RevocationProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The control plane as the application's own code calls it (`CTL-65`), wherever the control plane is wired
 * ([CoSecControlAutoConfiguration] imports it): the narrow set of the control API, each call the route itself, decided
 * for the principal its `ControlCaller` states. A service caller is admitted as the gate admits a forwarded identity,
 * with this host's identity limits (`cosec.pipeline.identity.*`).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PipelineProperties::class)
internal class ControlServiceConfiguration {
    /** Replaceable, by another implementation of the same calls (a client of a control-plane host). */
    @Bean
    @ConditionalOnMissingBean
    fun cosecControlService(
        plane: ControlPlane,
        properties: ControlProperties,
        pipeline: PipelineProperties,
        revocation: RevocationProperties,
        idGenerator: ObjectProvider<IdGenerator>,
    ): ControlService {
        val ids = idGenerator.getIfAvailable { RandomIdGenerator }
        val gate = IdentityGate(
            maxBytes = pipeline.identity.maxSize.toBytes().toInt(),
            maxAge = pipeline.identity.maxAge,
            skew = revocation.skew,
            idGenerator = ids,
        )
        return LocalControlService(plane, gate, AppId(properties.appId), ids = ids)
    }
}
