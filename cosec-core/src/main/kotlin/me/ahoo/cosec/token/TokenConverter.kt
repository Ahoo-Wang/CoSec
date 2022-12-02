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
package me.ahoo.cosec.token

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.token.AccessToken
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.api.token.TokenPrincipal

/**
 * Token Converter.
 *
 * @author ahoo wang
 */
interface TokenConverter {
    fun asToken(principal: CoSecPrincipal): CompositeToken
    fun <T : TokenPrincipal> asPrincipal(accessToken: AccessToken): T

    @Throws(TokenExpiredException::class)
    fun <T : TokenPrincipal> refresh(token: CompositeToken): T
}
