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
package me.ahoo.cosec.jwt

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.TokenExpiredException
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.JWTVerifier
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.RoleCapable
import me.ahoo.cosec.api.tenant.TenantCapable
import me.ahoo.cosec.api.token.AccessToken
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.context.request.RequestTenantIdParser
import me.ahoo.cosec.token.SimpleCompositeToken
import me.ahoo.cosec.token.TokenConverter
import me.ahoo.cosid.IdGenerator
import java.time.Duration
import java.util.*

/**
 * Jwt Token Converter.
 *
 * @author ahoo wang
 */
class JwtTokenConverter(
    private val idGenerator: IdGenerator,
    private val algorithm: Algorithm,
    private val accessTokenValidity: Duration = Duration.ofMinutes(10),
    private val refreshTokenValidity: Duration = Duration.ofDays(7)
) : TokenConverter {
    private val jwtVerifier: JWTVerifier = JWT.require(algorithm).build()

    override fun asToken(principal: CoSecPrincipal): CompositeToken {
        val accessTokenId = idGenerator.generateAsString()
        val now = Date()
        val accessTokenExp = Date(System.currentTimeMillis() + accessTokenValidity.toMillis())
        val payloadClaims: Map<String, *> = principal.attrs
            .filter {
                !Jwts.isRegisteredClaim(it.key)
            }
            .toMap()

        val accessTokenBuilder = JWT.create()
            .withJWTId(accessTokenId)
            .withSubject(principal.id)
            .withClaim(CoSecPrincipal.NAME_KEY, principal.name)
            .withClaim(RoleCapable.ROLE_KEY, principal.roles.joinToString(Jwts.ROLE_DELIMITER))
            .withPayload(payloadClaims)
            .withIssuedAt(now)
            .withExpiresAt(accessTokenExp)
        if (principal is TenantCapable) {
            val tenantCapable = principal as TenantCapable
            accessTokenBuilder
                .withClaim(RequestTenantIdParser.TENANT_ID_KEY, tenantCapable.tenant.tenantId)
        }
        val accessToken = accessTokenBuilder.sign(algorithm)
        val refreshTokenId = idGenerator.generateAsString()
        val refreshTokenExp = Date(System.currentTimeMillis() + refreshTokenValidity.toMillis())
        val refreshToken = JWT.create()
            .withJWTId(refreshTokenId)
            .withSubject(accessTokenId)
            .withIssuedAt(now)
            .withExpiresAt(refreshTokenExp)
            .sign(algorithm)
        return SimpleCompositeToken(accessToken, refreshToken)
    }

    override fun <T : TokenPrincipal> asPrincipal(accessToken: AccessToken): T {
        val decodedAccessToken = jwtVerifier.verify(accessToken.accessToken)
        return Jwts.asPrincipal(decodedAccessToken)
    }

    override fun <T : TokenPrincipal> refresh(token: CompositeToken): T {
        val decodedRefreshToken: DecodedJWT = try {
            jwtVerifier.verify(token.refreshToken)
        } catch (tokenExpiredException: TokenExpiredException) {
            throw me.ahoo.cosec.token.TokenExpiredException(tokenExpiredException.message!!, tokenExpiredException)
        }
        val decodedAccessToken = Jwts.decode(token.accessToken)
        require(decodedRefreshToken.subject == decodedAccessToken.id) { "Illegal refreshToken." }
        return Jwts.asPrincipal(decodedAccessToken)
    }
}
