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
package me.ahoo.cosec.spring.boot.starter.ip2region

import me.ahoo.cosec.spring.boot.starter.ENABLED_SUFFIX_KEY
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Conditional On Ip2Region Enabled.
 *
 * @author ahoo wang
 */
@ConditionalOnProperty(
    value = [ConditionalOnIp2RegionEnabled.ENABLED_KEY],
    matchIfMissing = true,
    havingValue = "true"
)
annotation class ConditionalOnIp2RegionEnabled {
    companion object {
        const val ENABLED_KEY = Ip2RegionProperties.PREFIX + ENABLED_SUFFIX_KEY
    }
}
