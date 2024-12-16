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
import me.ahoo.cosec.api.tenant.Tenant.Companion.TENANT_ID_KEY
import me.ahoo.cosec.api.token.AccessToken
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.api.token.TokenTenantPrincipal
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosec.token.PrincipalConverter
import me.ahoo.cosec.token.SimpleTokenPrincipal
import me.ahoo.cosec.token.SimpleTokenTenantPrincipal

/**
 * Jwts .
 *
 * @author ahoo wang
 */
object Jwts : PrincipalConverter {
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
            TENANT_ID_KEY == key ||
            PolicyCapable.POLICY_KEY == key ||
            RoleCapable.ROLE_KEY == key
    }

    fun String.removeBearerPrefix(): String {
        return if (this.startsWith(TOKEN_PREFIX)) {
            this.substring(TOKEN_PREFIX.length)
        } else {
            this
        }
    }

    fun decode(token: String): DecodedJWT {
        val jwtToken = token.removeBearerPrefix()
        return jwtParser.decodeJwt(jwtToken)
    }

    fun <T : TokenPrincipal> toPrincipal(decodedAccessToken: DecodedJWT): T {
        val accessTokenId = decodedAccessToken.id
        val principalId = decodedAccessToken.subject
        val attributes = decodedAccessToken
            .claims
            .asSequence()
            .filter { !isRegisteredClaim(it.key) }
            .associateBy({ it.key }, { it.value.asString() })

        val policyClaim = decodedAccessToken.getClaim(PolicyCapable.POLICY_KEY)
        val policies = if (policyClaim.isMissing) emptySet() else policyClaim.asList(String::class.java).toSet()

        val rolesClaim = decodedAccessToken.getClaim(RoleCapable.ROLE_KEY)
        val roles = if (rolesClaim.isMissing) emptySet() else rolesClaim.asList(String::class.java).toSet()
        val principal = SimplePrincipal(principalId, policies, roles, attributes)
        val tenantId = decodedAccessToken.getClaim(TENANT_ID_KEY).asString()
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
    fun <T : TokenPrincipal> toPrincipal(accessToken: String): T {
        val decodedAccessToken = decode(accessToken)
        return toPrincipal(decodedAccessToken)
    }

    override fun toPrincipal(accessToken: AccessToken): CoSecPrincipal {
        return toPrincipal(accessToken.accessToken)
    }
}
