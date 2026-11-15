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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.login.token

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.login.Authentication
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier
import java.time.Instant

/**
 * Switches a signed-in principal to another tenant, and to an app there (`wiki/integration/authentication.md`), to
 * issue a token for it: the second step of a sign-in that first proves the user and issues a token for no tenant or
 * app, and every later change of tenant or app.
 *
 * Roles, policies and attributes are a tenant's: an administrator of one tenant is not one of another. So the subject
 * this answers has the [previous][SwitchTenantCredentials.context] principal's id, the target tenant, and only what
 * [tenantSwitch] answers for that tenant; nothing else of the previous token reaches the new one (RC1-23). The app of
 * the new token is the issuer's decision (`SCP-12`): the one [tenantSwitch] answers ([TenantMembership.app]), checked
 * as a token's `aud` is, else the previous token's. [SwitchTenantCredentials.targetAppId] reaches the token only through
 * that answer, so a user cannot pick an app by naming it (`SCP-11`). The login time is kept: the new token starts a
 * session of its own, which ends when the previous one would, since the user did not sign in again (RFC 0009). It is
 * read from [SwitchTenantCredentials.accessToken], which [tokenVerifier] verifies and which must be the context's
 * principal's in the context's tenant.
 *
 * @param credentialsType the application's credentials class, by which a login finds this authentication
 */
class SwitchTenantAuthentication<C : SwitchTenantCredentials>(
    private val tokenVerifier: TokenVerifier,
    override val credentialsType: Class<C>,
    private val tenantSwitch: TenantSwitch<C>,
) : Authentication<C> {
    override suspend fun authenticate(credentials: C): TokenSubject {
        val previous = credentials.context
        check(previous.principal.authenticated) { "An anonymous request has no tenant to switch from." }
        val authTime = authTimeOf(credentials.accessToken, previous)
        return inTarget(previous, credentials.targetTenantId, tenantSwitch.switchTenant(credentials), authTime)
    }

    /** When the user signed in, from the token they switch from, which must be the context's principal's. */
    private fun authTimeOf(accessToken: String, previous: SecurityContext): Instant {
        val token = tokenVerifier.verify(accessToken)
        if (token.principal != previous.principal.id || (token.tenant ?: TenantId.DEFAULT.value) != previous.tenantId) {
            throw TokenVerificationException("The token presented to switch tenant is not the request's principal's.")
        }
        val session = token.session ?: throw TokenVerificationException("The token belongs to no session: sign in again.")
        return session.authTime
    }

    private fun inTarget(
        previous: SecurityContext,
        target: String,
        membership: TenantMembership,
        authTime: Instant,
    ): TokenSubject =
        TokenSubject(
            id = previous.principal.id,
            app = membership.app?.also(::requireAppId) ?: previous.scope.appId.ifEmpty { null },
            tenant = target,
            roles = membership.roles,
            policies = membership.policies,
            attributes = membership.attributes,
            membershipMarker = if (membership.fromProvider) MembershipClaims.PROVIDER else null,
            authTime = authTime,
        )

    /** The answered app must be one a token can carry as its `aud` (`SCP-10`). */
    private fun requireAppId(app: String) {
        require(ScopeResolver.isValidAppId(app)) { "The tenant switch answered an app that cannot be an app id." }
    }
}
