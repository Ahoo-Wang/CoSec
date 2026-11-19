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

/**
 * A token whose signature verified but which is not valid yet: its issue time (`iat`) or not-before time (`nbf`) is
 * later than the verifier's clock by more than the skew it tolerates, an issuer's clock running ahead of the verifier's
 * (`cosec.authentication.revocation.skew`). The authenticator rejects it as `TOKEN_NOT_YET_VALID`, which the client
 * reads as `token-invalid`, and the decision event names, so that an operator sees a clock rather than a signature.
 */
class TokenNotYetValidException : TokenVerificationException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}
