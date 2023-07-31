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
import me.ahoo.cosec.spring.boot.starter.EnabledCapable
import me.ahoo.cosec.spring.boot.starter.authorization.AuthorizationProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * CacheProperties .
 *
 * @author ahoo wang
 */
@ConfigurationProperties(prefix = CacheProperties.PREFIX)
class CacheProperties(
    @DefaultValue("true") override var enabled: Boolean = true,
    @DefaultValue(CoSec.COSEC) var keyPrefix: String = CoSec.COSEC
) : EnabledCapable {
    companion object {
        const val PREFIX: String = AuthorizationProperties.PREFIX + ".cache"
    }

    val globalPolicyIndexKey: String = "$keyPrefix:global:policy"
    val policyKeyPrefix: String = "$keyPrefix:policy:"
    val appPermissionKeyPrefix: String = "$keyPrefix:app:permission:"
    val rolePermissionKeyPrefix: String = "$keyPrefix:role:permission:"
}
