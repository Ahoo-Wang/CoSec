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

package me.ahoo.cosec.spring.boot.pipeline

import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.ConfigurationCondition

/**
 * On a host whose web adapter takes its caller from the `CoSec-Identity` header (the `IdentityGate`): a service behind a
 * gateway (`cosec.pipeline.mode=gateway`), or a host that decides with policies and believes the identity it is sent
 * (`cosec.pipeline.caller=forwarded-identity`), such as a control host. The guards of what such a host trusts (`STA-6`,
 * `STA-7`, `STA-9`) follow the gate, not the mode.
 */
@Conditional(OnIdentityGate::class)
internal annotation class ConditionalOnIdentityGate

internal class OnIdentityGate : AnyNestedCondition(ConfigurationCondition.ConfigurationPhase.PARSE_CONFIGURATION) {
    @ConditionalOnProperty(value = [PipelineProperties.MODE_KEY], havingValue = "gateway")
    class GatewayMode

    @ConditionalOnProperty(value = [PipelineProperties.CALLER_KEY], havingValue = "forwarded-identity")
    class ForwardedCaller
}

/** Whether the properties wire the identity gate ([OnIdentityGate]), for the check that follows a gate wired another way. */
internal object IdentityGateWiring {
    fun byProperty(environment: org.springframework.core.env.Environment): Boolean =
        environment.getProperty(PipelineProperties.MODE_KEY).equals("gateway", ignoreCase = true) ||
            environment.getProperty(PipelineProperties.CALLER_KEY).equals("forwarded-identity", ignoreCase = true)
}
