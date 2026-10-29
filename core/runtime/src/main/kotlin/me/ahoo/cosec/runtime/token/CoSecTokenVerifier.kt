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

package me.ahoo.cosec.runtime.token

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.token.TokenVerifier

/**
 * A [TokenVerifier] of CoSec's own tokens: `cosec-jwt`'s `JwtTokenVerifier`, and nothing else. Only a token one of
 * these verified may use break-glass (RFC 0009 §4.2, `BRK-8`): `TokenAuthenticator` decides it by which verifier
 * verified the token, never by a name a credential carries (RC1-26).
 *
 * Internal, so an application's verifier never implements it: a token a custom [TokenVerifier] accepts gets no
 * break-glass, whatever it holds. A verifier that wraps one of these is not one either.
 */
@InternalCoSecApi
interface CoSecTokenVerifier : TokenVerifier
