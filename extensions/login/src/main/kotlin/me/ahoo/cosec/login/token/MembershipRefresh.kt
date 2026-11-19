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

package me.ahoo.cosec.login.token

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.spi.token.VerifiedToken

/**
 * SPI: what a user holds now, when its token is refreshed ([RefreshTokenAuthentication]). The refresh has verified the
 * pair and checked its revocation before it asks, and rotates the session only after this answers.
 *
 * An implementation that looks the user up again makes changed roles apply to the next refreshed token. [CLAIMS], what
 * the starter's refresh route uses unless the application declares a `MembershipRefresh` bean, looks nothing up: the
 * new token carries the roles the old one claimed, so a role removed from a user stays in every refreshed token of its
 * session until the user signs in again, or until the application revokes the user's tokens
 * ([me.ahoo.cosec.spi.token.TokenRevoker.revokeSubject]). [RefreshTokenAuthentication] takes one with no default: an
 * application that keeps the claims writes [CLAIMS].
 *
 * It is given the application's own refresh credentials, [C], as a tenant switch's `TenantSwitch` is given its
 * credentials: what else of the request the check reads (the device a trusted-device check compares, say) the
 * application's endpoint puts there, and the hook reads it without a cast.
 *
 * Refuse the refresh, when the user may no longer sign in, by throwing a
 * [me.ahoo.cosec.spi.token.TokenVerificationException] ([me.ahoo.cosec.spi.token.TokenRevokedException] when the
 * membership is gone): the refresh route the starter serves answers it `401`, and the client signs in again. Any other
 * exception is an error, not a refusal: `500`, and the client keeps its pair. Answer null to keep what the refreshed
 * token claimed. The answer's [TenantMembership.app] is null or the token's own: a refresh never changes the app a
 * token is for.
 */
@CoSecSpi
fun interface MembershipRefresh<in C : RefreshTokenCredentials> {
    suspend fun refresh(token: VerifiedToken, credentials: C): TenantMembership?

    companion object {
        /**
         * Keeps what the refreshed token claimed, for any credentials class: a removed role applies only after a new
         * sign-in or a subject revocation.
         */
        val CLAIMS: MembershipRefresh<RefreshTokenCredentials> = MembershipRefresh { _, _ -> null }
    }
}
