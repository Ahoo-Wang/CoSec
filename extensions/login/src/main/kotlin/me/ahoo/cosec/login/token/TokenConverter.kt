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
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.spi.token.TokenVerifier
import java.time.Duration

/**
 * Issues tokens.
 *
 * This interface is used to generate tokens (access and refresh)
 * for what a [TokenSubject] says.
 *
 * A pair issued for a subject without a session ([TokenSubject.session]) never refreshes: a refresh needs the session
 * its tokens carry. Issue through `TokenCompositeAuthentication`, which starts one, or start one with
 * `TokenFamilies.start` and pass `subject.inSession(issue)`.
 *
 * @see TokenVerifier
 */
@CoSecSpi
interface TokenConverter {
    /**
     * Issues a token with default validity periods.
     *
     * @param subject What the token says
     * @return The generated composite token
     */
    fun toToken(subject: TokenSubject): CompositeToken

    /**
     * Issues a token with custom validity periods.
     *
     * @param subject What the token says
     * @param accessTokenValidity Duration for which access token is valid
     * @param refreshTokenValidity Duration for which refresh token is valid
     * @return The generated composite token
     */
    fun toToken(
        subject: TokenSubject,
        accessTokenValidity: Duration,
        refreshTokenValidity: Duration
    ): CompositeToken
}
