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
package me.ahoo.cosec.spring.boot.starter.jwt

import me.ahoo.cosec.spring.boot.starter.ENABLED_SUFFIX_KEY
import me.ahoo.cosec.spring.boot.starter.pipeline.ConditionalOnEnforceMode
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.ConfigurationCondition

/**
 * On `cosec.jwt.enabled`. Unset, it follows `cosec.pipeline.mode`: on where the host verifies tokens itself, off on a
 * service behind a gateway, which verifies none (it issues them only when it turns this on).
 *
 * @author ahoo wang
 */
@Conditional(OnJwtEnabled::class)
annotation class ConditionalOnJwtEnabled {
    companion object {
        const val ENABLED_KEY = JwtProperties.PREFIX + ENABLED_SUFFIX_KEY
    }
}

internal class OnJwtEnabled : AnyNestedCondition(ConfigurationCondition.ConfigurationPhase.PARSE_CONFIGURATION) {
    @ConditionalOnProperty(value = [ConditionalOnJwtEnabled.ENABLED_KEY], havingValue = "true")
    class TurnedOn

    @ConditionalOnEnforceMode
    @ConditionalOnProperty(value = [ConditionalOnJwtEnabled.ENABLED_KEY], havingValue = "true", matchIfMissing = true)
    class Enforcing
}
