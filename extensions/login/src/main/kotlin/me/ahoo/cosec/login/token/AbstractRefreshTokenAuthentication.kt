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

import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.Authentication
import me.ahoo.cosec.login.Credentials
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.TokenVerifier

abstract class AbstractRefreshTokenAuthentication<C : RefreshTokenCredentials>(
    override val supportCredentials: Class<C>
) : Authentication<C>

/**
 * Refreshes a token pair (`authentication.md`, Refreshing tokens): [tokenVerifier] checks the pair, pure computation on
 * the caller's thread, then [revocation] reads whether the access token, or every token of its principal, was revoked,
 * suspending without blocking. The subject is what the old access token said, for the new token to say again.
 *
 * A token is never issued on a revocation read that failed (`IDN-14`): when the revocations cannot be read, the
 * [me.ahoo.cosec.spi.token.RevocationUnavailableException] fails the refresh, which the application answers 503. Were the
 * refresh to read it as "not revoked", the new token would carry neither a revoked id nor an `iat` before a principal's
 * revocation, and would outlive both.
 */
class SimpleRefreshTokenAuthentication(
    private val tokenVerifier: TokenVerifier,
    private val revocation: TokenRevocation,
) : AbstractRefreshTokenAuthentication<RefreshTokenCredentials>(RefreshTokenCredentials::class.java) {
    override suspend fun authenticate(credentials: RefreshTokenCredentials): TokenSubject {
        val token = tokenVerifier.refresh(credentials)
        revocation.check(token)
        return TokenSubject.of(token)
    }
}

interface RefreshTokenCredentials : Credentials, CompositeToken
