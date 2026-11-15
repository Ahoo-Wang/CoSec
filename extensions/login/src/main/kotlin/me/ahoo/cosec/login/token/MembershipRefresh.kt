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
 * SPI: what a user holds now, when its token is refreshed ([RefreshTokenAuthentication]), looked up again so that
 * changed roles apply to the new token, never copied from the old one by default. The refresh has verified the pair
 * and checked its revocation before it asks, and rotates the session only after this answers.
 *
 * An exception refuses the refresh: throw when the user may no longer sign in. Answer null to keep what the refreshed
 * token claimed. The answer's [TenantMembership.app] is null or the token's own: a refresh never changes the app a
 * token is for.
 */
@CoSecSpi
fun interface MembershipRefresh {
    suspend fun refresh(token: VerifiedToken): TenantMembership?

    companion object {
        /** Keeps what the refreshed token claimed: the default. */
        val CLAIMS: MembershipRefresh = MembershipRefresh { null }
    }
}
