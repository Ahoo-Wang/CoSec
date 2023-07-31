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

import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.spring.boot.starter.EnabledCapable
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * InjectSecurityContextProperties .
 *
 * @author ahoo wang
 */
@ConfigurationProperties(prefix = Ip2RegionProperties.PREFIX)
data class Ip2RegionProperties(@DefaultValue("true") override var enabled: Boolean = true) : EnabledCapable {

    companion object {
        const val PREFIX = CoSec.COSEC_PREFIX + "ip2region"
    }
}
