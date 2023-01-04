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
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.PolicyCapable
import me.ahoo.cosec.api.principal.RoleCapable
import me.ahoo.cosec.api.tenant.Tenant.Companion.TENANT_ID_KEY
import me.ahoo.cosec.api.tenant.TenantCapable
import me.ahoo.cosec.api.token.CompositeToken
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

    override fun asToken(principal: CoSecPrincipal): CompositeToken {
        val accessTokenId = idGenerator.generateAsString()
        val now = Date()
        val accessTokenExp = Date(System.currentTimeMillis() + accessTokenValidity.toMillis())
        val payloadClaims: Map<String, *> = principal.attributes
            .filter {
                !Jwts.isRegisteredClaim(it.key)
            }
            .toMap()

        val accessTokenBuilder = JWT.create()
            .withJWTId(accessTokenId)
            .withSubject(principal.id)
            .withClaim(PolicyCapable.POLICY_KEY, principal.policies.toList())
            .withClaim(RoleCapable.ROLE_KEY, principal.roles.toList())
            .withPayload(payloadClaims)
            .withIssuedAt(now)
            .withExpiresAt(accessTokenExp)
        if (principal is TenantCapable) {
            val tenantCapable = principal as TenantCapable
            accessTokenBuilder
                .withClaim(TENANT_ID_KEY, tenantCapable.tenant.tenantId)
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
}
