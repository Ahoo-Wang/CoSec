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

package me.ahoo.cosec.authentication.token

import me.ahoo.cosec.api.authentication.Authentication
import me.ahoo.cosec.api.authentication.Credentials
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.token.TokenVerifier
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

abstract class AbstractRefreshTokenAuthentication<C : RefreshTokenCredentials, out P : CoSecPrincipal>(
    override val supportCredentials: Class<C>
) : Authentication<C, P>

class SimpleRefreshTokenAuthentication(private val tokenVerifier: TokenVerifier) :
    AbstractRefreshTokenAuthentication<RefreshTokenCredentials, CoSecPrincipal>(RefreshTokenCredentials::class.java) {
    override fun authenticate(credentials: RefreshTokenCredentials): Mono<CoSecPrincipal> {
        return Mono.defer {
            tokenVerifier.refresh<TokenPrincipal>(credentials).toMono()
        }
    }
}

interface RefreshTokenCredentials : Credentials, CompositeToken
