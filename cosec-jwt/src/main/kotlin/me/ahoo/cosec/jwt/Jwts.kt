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
import com.auth0.jwt.RegisteredClaims
import com.auth0.jwt.interfaces.DecodedJWT
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.PolicyCapable
import me.ahoo.cosec.api.principal.RoleCapable
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.api.token.TokenTenantPrincipal
import me.ahoo.cosec.context.request.RequestTenantIdParser
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosec.token.SimpleAccessToken
import me.ahoo.cosec.token.SimpleTokenPrincipal
import me.ahoo.cosec.token.SimpleTokenTenantPrincipal

/**
 * Jwts .
 *
 * @author ahoo wang
 */
object Jwts {
    const val AUTHORIZATION_KEY = "authorization"
    const val TOKEN_PREFIX = "Bearer "
    private val jwtParser = JWT()

    fun isRegisteredClaim(key: String): Boolean {
        return RegisteredClaims.ISSUER == key ||
            RegisteredClaims.SUBJECT == key ||
            RegisteredClaims.EXPIRES_AT == key ||
            RegisteredClaims.NOT_BEFORE == key ||
            RegisteredClaims.ISSUED_AT == key ||
            RegisteredClaims.JWT_ID == key ||
            RegisteredClaims.AUDIENCE == key ||
            CoSecPrincipal.NAME_KEY == key ||
            RequestTenantIdParser.TENANT_ID_KEY == key ||
            PolicyCapable.POLICY_KEY == key ||
            RoleCapable.ROLE_KEY == key
    }

    @JvmStatic
    fun parseAccessToken(authorization: String?): SimpleAccessToken? {
        if (authorization?.startsWith(TOKEN_PREFIX) != true) {
            return null
        }
        val accessToken = authorization.substring(TOKEN_PREFIX.length)
        return SimpleAccessToken(accessToken)
    }

    fun decode(token: String): DecodedJWT {
        return jwtParser.decodeJwt(token)
    }

    fun <T : TokenPrincipal> asPrincipal(decodedAccessToken: DecodedJWT): T {
        val accessTokenId = decodedAccessToken.id
        val principalId = decodedAccessToken.subject
        val name = decodedAccessToken.getClaim(CoSecPrincipal.NAME_KEY).asString()
        val attrs = decodedAccessToken
            .claims
            .filter { !isRegisteredClaim(it.key) }

        val policyClaim = decodedAccessToken.getClaim(PolicyCapable.POLICY_KEY)
        val policies = if (policyClaim.isMissing) emptySet() else policyClaim.asList(String::class.java).toSet()

        val rolesClaim = decodedAccessToken.getClaim(RoleCapable.ROLE_KEY)
        val roles = if (rolesClaim.isMissing) emptySet() else rolesClaim.asList(String::class.java).toSet()
        val principal = SimplePrincipal(principalId, name, policies, roles, attrs)
        val tenantId = decodedAccessToken.getClaim(RequestTenantIdParser.TENANT_ID_KEY).asString()
        val tokenPrincipal = SimpleTokenPrincipal(accessTokenId, principal)
        if (tenantId.isNullOrEmpty()) {
            @Suppress("UNCHECKED_CAST")
            return tokenPrincipal as T
        }
        @Suppress("UNCHECKED_CAST")
        return SimpleTokenTenantPrincipal(delegate = tokenPrincipal, tenant = SimpleTenant(tenantId)) as T
    }

    /**
     * Convert string token to [TokenTenantPrincipal] without verify.
     *
     * @param accessToken accessToken
     * @param <T> type of TokenPrincipal
     * @return TokenTenantPrincipal
     */
    @JvmStatic
    fun <T : TokenPrincipal> asPrincipal(accessToken: String): T {
        val decodedAccessToken = decode(accessToken)
        return asPrincipal(decodedAccessToken)
    }
}
