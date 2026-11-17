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
package me.ahoo.cosec.spring.boot.jwt

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.spring.boot.CoSecProperties
import me.ahoo.cosec.spring.boot.EnabledCapable
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
@InternalCoSecApi
class JwtProperties(
    /** Whether JWTs are issued, verified and revoked; unset, on where the host verifies tokens itself and off behind a gateway. */
    @DefaultValue("true") override var enabled: Boolean = true,
    /** The signing algorithm; issuers and verifiers must agree. */
    @DefaultValue("hmac256") var algorithm: Algorithm = Algorithm.HMAC256,
    /**
     * The signing secret, required while JWT is enabled: startup fails when it is blank or shorter than the algorithm
     * needs in UTF-8 bytes (32 for hmac256, 48 for hmac384, 64 for hmac512).
     */
    @DefaultValue("") var secret: String = "",
    @NestedConfigurationProperty var tokenValidity: TokenValidity = TokenValidity(),
    @NestedConfigurationProperty var session: Session = Session(),
) : EnabledCapable {
    @InternalCoSecApi
    companion object {
        const val PREFIX = CoSecProperties.PREFIX + ".jwt"
    }

    @InternalCoSecApi
    class TokenValidity(
        /** The lifetime of an access token. */
        var access: Duration = Duration.ofMinutes(10),
        /** The lifetime of a refresh token; a revocation is kept this long, plus one second. */
        var refresh: Duration = Duration.ofDays(7)
    )

    /** `cosec.jwt.session.*`: the refresh token families (RFC 0009). */
    @InternalCoSecApi
    class Session(
        /** How long a session lasts from its login, however often it is refreshed; then the user signs in again. */
        var lifetime: Duration = TokenFamilies.DEFAULT_LIFETIME,
        /**
         * How long after a refresh the pair it replaced is answered at the current generation: the window a client has to
         * retry a refresh whose response it lost.
         */
        var rotationLeeway: Duration = TokenFamilies.DEFAULT_LEEWAY,
    )

    /**
     * [minSecretBytes]: the shortest `secret` each algorithm accepts, in UTF-8 bytes, its hash's output size (RFC 7518
     * §3.2). A shorter one can be guessed offline from any token issued with it (RC1-24).
     */
    @InternalCoSecApi
    enum class Algorithm(val minSecretBytes: Int) {
        HMAC256(32), HMAC384(48), HMAC512(64)
    }
}
