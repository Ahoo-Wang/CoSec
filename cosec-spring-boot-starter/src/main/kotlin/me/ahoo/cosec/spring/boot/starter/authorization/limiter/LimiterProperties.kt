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
package me.ahoo.cosec.spring.boot.starter.authorization.limiter

import me.ahoo.cosec.cache.limiter.DEFAULT_REDIS_RATE_LIMITER_KEY_PREFIX
import me.ahoo.cosec.spring.boot.starter.ENABLED_SUFFIX_KEY
import me.ahoo.cosec.spring.boot.starter.EnabledCapable
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * CoSec Redis Rate Limiter Properties.
 *
 * @author ahoo wang
 */
@ConfigurationProperties(LimiterProperties.PREFIX)
data class LimiterProperties(
    @DefaultValue("true") override var enabled: Boolean = true,
    @DefaultValue(DEFAULT_REDIS_RATE_LIMITER_KEY_PREFIX) var keyPrefix: String = DEFAULT_REDIS_RATE_LIMITER_KEY_PREFIX,
) : EnabledCapable {
    companion object {
        const val PREFIX: String = "cosec.limiter"
        const val ENABLED_KEY: String = PREFIX + ENABLED_SUFFIX_KEY
    }
}
