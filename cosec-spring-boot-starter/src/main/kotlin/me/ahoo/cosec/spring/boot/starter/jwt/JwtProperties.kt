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

import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.spring.boot.starter.EnabledCapable
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

/**
 * Jwt Properties .
 *
 * @author ahoo wang
 */
@ConfigurationProperties(prefix = JwtProperties.PREFIX)
class JwtProperties(
    @DefaultValue("true") override var enabled: Boolean = true,
    @DefaultValue("hmac256") var algorithm: Algorithm = Algorithm.HMAC256,
    var secret: String,
    @NestedConfigurationProperty var tokenValidity: TokenValidity = TokenValidity()
) : EnabledCapable {
    companion object {
        const val PREFIX = CoSec.COSEC_PREFIX + "jwt"
    }

    data class TokenValidity(
        var access: Duration = Duration.ofMinutes(10),
        var refresh: Duration = Duration.ofDays(7)
    )

    enum class Algorithm {
        HMAC256, HMAC384, HMAC512
    }
}
