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

import me.ahoo.cosec.spring.boot.starter.ENABLED_SUFFIX_KEY
import me.ahoo.cosec.spring.boot.starter.EnabledCapable
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * CoSec rate limiting properties.
 *
 * @author ahoo wang
 */
@ConfigurationProperties(LimiterProperties.PREFIX)
data class LimiterProperties(
    @DefaultValue("true") override var enabled: Boolean = true,
    /**
     * Resource patterns of the rate limit rule files. Each file holds one rule or an array of rules.
     */
    @DefaultValue(DEFAULT_LOCATION) var locations: Set<String> = setOf(DEFAULT_LOCATION),
    /**
     * Redis key prefix of the counters, when the Redis rate limiter is in use.
     */
    @DefaultValue(DEFAULT_KEY_PREFIX) var keyPrefix: String = DEFAULT_KEY_PREFIX,
) : EnabledCapable {
    companion object {
        const val PREFIX: String = "cosec.limiter"
        const val ENABLED_KEY: String = PREFIX + ENABLED_SUFFIX_KEY
        const val DEFAULT_LOCATION = "classpath:cosec-rate-limit/*-rate-limit.json"
        const val DEFAULT_KEY_PREFIX = "cosec:rate-limiter"
    }
}
