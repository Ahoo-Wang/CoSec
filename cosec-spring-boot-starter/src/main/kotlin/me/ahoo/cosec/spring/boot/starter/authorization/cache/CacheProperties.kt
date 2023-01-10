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
package me.ahoo.cosec.spring.boot.starter.authorization.cache

import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.spring.boot.starter.authorization.AuthorizationProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

/**
 * CacheProperties .
 *
 * @author ahoo wang
 */
@ConstructorBinding
@ConfigurationProperties(prefix = CacheProperties.PREFIX)
data class CacheProperties(val enabled: Boolean = true, val cacheKeyPrefix: CacheKeyPrefix = CacheKeyPrefix()) {
    companion object {
        const val PREFIX: String = AuthorizationProperties.PREFIX + ".cache"
    }

    data class CacheKeyPrefix(
        var globalPolicyIndex: String = CoSec.COSEC + ":global:policy",
        var policy: String = CoSec.COSEC + ":policy:",
        var rolePolicy: String = CoSec.COSEC + ":role:policy:",
    )
}
