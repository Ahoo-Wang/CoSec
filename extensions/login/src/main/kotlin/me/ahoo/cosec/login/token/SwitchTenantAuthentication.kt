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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.login.token

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.Authentication
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier

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
 * that answer, so a user cannot pick an app by naming it (`SCP-11`).
 *
 * It issues from the session the request proved, as the pipeline authenticated it with CoSec's own token, and only from
 * that session's token pair while the session is live (`IDN-22`). The switch presents the pair a refresh presents: the
 * request's own access token and its refresh token, checked by [tokenVerifier] as a refresh's are; an access token alone,
 * or a pair the request was not made with, switches nothing. Its revocations are read strictly, its family neither revoked
 * (a logout, a pair presented again) nor ended, and the pair one the session would refresh, by the refresh's own rule: the
 * current generation, or the one just replaced within the rotation leeway, while an older pair presented again revokes
 * the session, as a refresh would. That is checked before [tenantSwitch] is asked, in the one place a refresh checks it
 * too. The switch moves nothing of the previous session, which refreshes on as before. The new token starts a session of
 * its own, which ends when the previous one would, since the user did not sign in again (`IDN-17`); the previous one is
 * left as it is, so a logout ends only the pair it is given.
 *
 * @param tokenVerifier CoSec's own verifier, which checks the pair as a refresh's ([TokenVerifier.refresh])
 * @param credentialsType the application's credentials class, by which a login finds this authentication
 */
class SwitchTenantAuthentication<C : SwitchTenantCredentials>(
    private val tokenVerifier: TokenVerifier,
    families: TokenFamilies,
    revocation: TokenRevocation,
    override val credentialsType: Class<C>,
    private val tenantSwitch: TenantSwitch<C>,
) : Authentication<C> {
    private val sessions = LiveSessions(revocation, families)

    override suspend fun authenticate(credentials: C): TokenSubject {
        val previous = credentials.context
        check(previous.principal.authenticated) { "An anonymous request has no tenant to switch from." }
        val principal = RequestSecurity.of(previous)?.principal
            ?: throw TokenVerificationException("A switch is asked in a request the pipeline permitted.")
        val token = tokenVerifier.refresh(CompositeToken(credentials.accessToken, credentials.refreshToken))
        if (token.id != principal.credential?.id || token.session == null || token.session != principal.session) {
            throw TokenVerificationException("The token pair presented is not the one the switch was asked with.")
        }
        val live = sessions.presented(token)
        val subject = inTarget(previous, credentials.targetTenantId, tenantSwitch.switchTenant(credentials))
        return subject.inSession(live.fork(TenantId(credentials.targetTenantId)))
    }

    private fun inTarget(previous: SecurityContext, target: String, membership: TenantMembership): TokenSubject =
        TokenSubject(
            id = previous.principal.id,
            kind = Identity.Credential.Kind.PERSON,
            app = membership.app?.also(::requireAppId) ?: previous.scope.appId.ifEmpty { null },
            tenant = target,
            roles = membership.roles,
            policies = membership.policies,
            attributes = membership.attributes,
            membershipMarker = if (membership.fromProvider) MembershipClaims.PROVIDER else null,
        )

    /** The answered app must be one a token can carry as its `aud` (`SCP-10`). */
    private fun requireAppId(app: String) {
        require(ScopeResolver.isValidAppId(app)) { "The tenant switch answered an app that cannot be an app id." }
    }
}
