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

package me.ahoo.cosec.spi.token

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.api.token.CompositeToken

/**
 * Verifies access tokens, and the pair a refresh presents: signatures, expiry and claims.
 *
 * Pure computation by contract: no I/O, no blocking, no state that changes between calls, so the pipeline calls it on
 * the request's thread, which may be an event loop, and a login calls it wherever it runs. Whether a token was
 * revoked is not its question: that is a [RevocationReader]'s, read by the caller after this answers (the pipeline's
 * `TokenAuthenticator`, refresh, `TokenRevoker`) through `TokenRevocation` of `cosec-runtime`, which is public and a
 * bean of the starter. Code that calls this outside the pipeline calls `TokenRevocation.check` with what it returns.
 *
 * A token's JWS `crit` header names extensions a reader must understand to read the token at all (RFC 7515 §4.1.11,
 * `IDN-47`): an implementation refuses a token whose `crit` names anything it does not understand, never reading it
 * without, with [TokenNotUnderstoodException] once the signature is verified (the pipeline answers `503`), and a
 * malformed `crit` with [TokenVerificationException]. A JWT library may not do it for you: `java-jwt` ignores `crit`.
 */
@CoSecSpi
interface TokenVerifier {
    /**
     * Verifies an access token and returns what it says.
     *
     * @param accessToken The access token to verify
     * @return What the token says
     * @throws TokenVerificationException if verification fails; [DelegationUnsupportedException] for a token that
     *   delegates (an `act` claim), which must never become a principal (`IDN-4`)
     * @throws TokenNotUnderstoodException for a token whose `crit` names what this verifier does not understand
     */
    @Throws(TokenVerificationException::class)
    fun verify(accessToken: String): VerifiedToken

    /**
     * Refreshes tokens using a refresh token.
     *
     * @param token The composite token containing refresh token
     * @return What the access token of [token] says, for the new token to say again
     * @throws TokenVerificationException if refresh fails
     * @throws TokenNotUnderstoodException when either token's `crit` names what this verifier does not understand
     */
    @Throws(TokenVerificationException::class)
    fun refresh(token: CompositeToken): VerifiedToken
}
