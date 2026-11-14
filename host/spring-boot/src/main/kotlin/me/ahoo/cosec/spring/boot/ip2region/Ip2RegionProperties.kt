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

/** `cosec.ip2region.*`: the `ext.ip2region.*` attribute provider (`cosec-ip2region`). */
@ConfigurationProperties(prefix = Ip2RegionProperties.PREFIX)
class Ip2RegionProperties(
    @DefaultValue("true") override var enabled: Boolean = true,
    /**
     * Where the IPv6 `.xdb` database is (a Spring resource location, `file:` or `classpath:`), loaded beside the bundled
     * IPv4 one. Unset, an IPv6 client's region is unavailable: a DENY on it is Indeterminate, never skipped (round 2
     * M5). A location that does not exist fails startup.
     */
    var ipv6Location: String? = null,
) : EnabledCapable {

    companion object {
        const val PREFIX = CoSec.COSEC_PREFIX + "ip2region"
    }
}
